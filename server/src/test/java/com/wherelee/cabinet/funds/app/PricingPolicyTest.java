package com.wherelee.cabinet.funds.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.SizeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingPolicyTest {

    private static PricingPolicy policy(long smallUnit, int freeMinutes, int dailyCapHours, int capDays) {
        PricingPolicy p = new PricingPolicy();
        ReflectionTestUtils.setField(p, "prepayHours", 4);
        ReflectionTestUtils.setField(p, "freeMinutes", freeMinutes);
        ReflectionTestUtils.setField(p, "largeUnit", 40L);
        ReflectionTestUtils.setField(p, "mediumUnit", 25L);
        ReflectionTestUtils.setField(p, "smallUnit", smallUnit);
        ReflectionTestUtils.setField(p, "dailyCapHours", dailyCapHours);
        ReflectionTestUtils.setField(p, "capDays", capDays);
        return p;
    }

    @Test
    @DisplayName("免费窗口内 0 元，窗口外不足一小时按一小时")
    void freeWindowThenRoundUp() {
        PricingPolicy p = policy(15, 10, 0, 0);

        assertEquals(0L, p.quote(SizeType.SMALL, 10).consumePoints());
        assertEquals(15L, p.quote(SizeType.SMALL, 11).consumePoints(), "第 11 分钟起算 1 小时");
        assertEquals(15L, p.quote(SizeType.SMALL, 70).consumePoints(), "60 分钟计费时长 = 1 小时");
        assertEquals(30L, p.quote(SizeType.SMALL, 71).consumePoints(), "61 分钟计费时长 = 2 小时");
    }

    @Test
    @DisplayName("日封顶：单日第 13 小时起不再增长，跨日后从零重算")
    void dailyCapFlattensWithinADay() {
        PricingPolicy p = policy(15, 0, 12, 0);

        assertEquals(12 * 15L, p.quote(SizeType.SMALL, 12 * 60).consumePoints(), "12 小时正好到日封顶");
        assertEquals(12 * 15L, p.quote(SizeType.SMALL, 24 * 60).consumePoints(), "第 13~24 小时不加钱");
        assertEquals(13 * 15L, p.quote(SizeType.SMALL, 25 * 60).consumePoints(), "跨日要重新给出一天的额度");
    }

    @Test
    @DisplayName("总封顶：超过 cap-days 的部分归零，账单有个能一句话说清的上限")
    void totalCapStopsGrowing() {
        PricingPolicy p = policy(15, 0, 12, 3);
        long cap = 3 * 12 * 15L;

        assertEquals(cap, p.quote(SizeType.SMALL, 3 * 24 * 60).consumePoints(), "第 3 日末尾正好封满");
        assertEquals(cap, p.quote(SizeType.SMALL, 30L * 24 * 60).consumePoints(), "30 天也不再多收一分");
        assertEquals(cap, p.quote(SizeType.SMALL, 365L * 24 * 60).consumePoints(), "放一年仍是同一个上限");
    }

    @Test
    @DisplayName("0 = 不限（旧快照语义）：阶梯参数没配时回到纯线性")
    void zeroMeansUnlimited() {
        PricingPolicy linear = policy(15, 0, 0, 0);
        long hours = 100L;

        assertEquals(hours * 15L, linear.quote(SizeType.SMALL, hours * 60).consumePoints());
        assertEquals(48 * 15L, policy(15, 0, 99, 0).quote(SizeType.SMALL, 48 * 60).consumePoints());
    }

    @Test
    @DisplayName("全区间扫描：计费小时数单调不减，且永不越过 cap-days × daily-cap")
    void chargeableHoursIsMonotoneAndBounded() {
        int dailyCap = 12;
        int capDays = 3;
        int ceiling = dailyCap * capDays;
        int previous = 0;

        for (int raw = 0; raw <= 200; raw++) {
            int now = PricingPolicy.chargeableHours(raw, dailyCap, capDays);
            assertTrue(now >= previous, "计费小时数不能随时长减少：raw=" + raw + " " + previous + "→" + now);
            assertTrue(now <= ceiling, "越过总封顶：raw=" + raw + " → " + now + " > " + ceiling);
            previous = now;
        }
        for (int raw = 1; raw <= 72; raw++) {
            assertEquals(raw, PricingPolicy.chargeableHours(raw, 24, 0), "线性口径下不该吞掉任何一小时");
        }
    }

    @Test
    @DisplayName("改配置不追溯：同一份快照在新旧配置下算出同一个价")
    void settlementReadsSnapshotNotCurrentConfig() {
        PricingPolicy cheap = policy(15, 10, 0, 0);
        PricingPolicy.Quote quote = cheap.quote(SizeType.SMALL, 50 * 60);
        assertEquals(50L * 15L, quote.consumePoints(), "无封顶时按小时线性");

        PricingPolicy strict = policy(999, 0, 3, 1);
        PricingPolicy.Quote settled = strict.fromSnapshot(quote.snapshot(), 50L * 60);

        assertEquals(50L * 15L, settled.consumePoints(), "结算只能按快照算：改价不得追溯进行中订单");
        assertEquals(15L, settled.unitPointsPerHour(), "单价取自快照而不是当前配置");
    }

    @Test
    @DisplayName("缺阶梯字段的旧快照读回线性语义，不被新默认值意外封顶")
    void legacySnapshotWithoutCapFieldsStaysLinear() {
        String legacy = "{\"strategy\":\"per-hour-fixed-deposit\",\"unitPointsPerHour\":15,"
                + "\"freeMinutes\":10,\"billedHours\":100,\"depositPoints\":200}";

        PricingPolicy nowCapped = policy(15, 10, 12, 3);
        PricingPolicy.Quote settled = nowCapped.fromSnapshot(legacy, 100L * 60 + 10);

        assertEquals(100L * 15L, settled.consumePoints(),
                "旧单没有 dailyCapHours/capDays 就当不限；按今天的 12/3 封顶等于追溯改价");
    }

    @Test
    @DisplayName("单价被写成字符串的历史快照仍能结算（第 12 刀的坑不能变成死单）")
    void legacyStringUnitStillResolves() {
        String dirty = "{\"unitPointsPerHour\":\"15\",\"freeMinutes\":10,"
                + "\"dailyCapHours\":0,\"capDays\":0,\"depositPoints\":200}";

        assertEquals(2 * 15L, policy(15, 10, 12, 3).fromSnapshot(dirty, 71).consumePoints());
    }

    @Test
    @DisplayName("没有快照就结不了算：明确报错而不是按当前配置猜一个价")
    void missingSnapshotFailsLoud() {
        PricingPolicy p = policy(15, 10, 12, 3);

        BizException blank = assertThrows(BizException.class, () -> p.fromSnapshot("", 60));
        assertEquals(ResultCode.SYSTEM_ERROR, blank.getResultCode());
        assertThrows(BizException.class, () -> p.fromSnapshot("{\"freeMinutes\":10}", 60),
                "缺单价必须停：拿 0 当默认值等于白送");
    }
}
