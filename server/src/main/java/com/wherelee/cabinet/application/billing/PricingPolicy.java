package com.wherelee.cabinet.application.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.SizeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计费策略（轻档：按尺寸单价 × 计费小时 + 每单固定押金，S-02/S-03）。
 *
 * <p><b>核心约束：结算只能用下单时写进 {@code pricing_snapshot} 的单价，不能读当前配置。</b>
 * 否则"运营改价"会追溯性地改变进行中的订单价格——用户看到的价格和实际扣的不一样，
 * 这类差异在真实系统里就是投诉与纠纷，而且事后无法复现当时算的是什么。
 * 所以快照既是审计材料，也是计算的输入，不只是"留个记录"。
 *
 * <p>规则要能一句话说清（复杂规则让账单不可解释）：
 * 免费窗口内不计费；超出后<b>不足一小时按一小时</b>；押金与消耗分栏，退押金不抵扣欠费。
 *
 * <p><b>阶梯与封顶（第 13 刀，规划 §5.10“超时未取阶梯计费至封顶”）</b>：
 * 每 24 个计费小时算一个计费日，<b>单日最多收 {@code daily-cap-hours} 小时的价</b>，
 * <b>超过 {@code cap-days} 天的部分不再计费</b>。为什么不让它线性涨：人不来取件往往是忘了/手机丢了，
 * 无上限增长到后面变成“取件要先交钱”的死结，反而收不到钱；封顶后这笔变成欠费追缴（第 12 刀已不锁件）。
 * 两个参数取 0 都是“不限”——<b>旧快照（第 12 刀那批单）读回来就是当时的线性语义，改价不得追溯</b>。
 */
@Component
public class PricingPolicy {

    private static final Logger log = LoggerFactory.getLogger(PricingPolicy.class);

    private static final long HOURS_IN_MINUTES = 60L;

    /** 一个计费日的小时数：阶梯分段用它，不用 24 字面量到处飘。 */
    private static final int HOURS_IN_DAY = 24;

    /**
     * 快照专用 mapper：<b>不能用 Spring 容器里那个</b>。
     *
     * <p>第 5 刀为了让雪花 ID 不在前端丢精度，全局把 {@code Long} 编成字符串。
     * 那是对的，但它只适用于“输出给人看”；拿它写定价快照，算术输入就变成了
     * {@code "unitPointsPerHour": "25"}（字符串），结算读不回来。本刀实测到这一点。
     * 规则：<b>展示层的编码器不得用来序列化业务算术数据</b>。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${cabinet.pricing.deposit-points:200}")
    private long depositPoints;

    @Value("${cabinet.pricing.free-minutes:10}")
    private int freeMinutes;

    @Value("${cabinet.pricing.unit-points-per-hour.LARGE:40}")
    private long largeUnit;

    @Value("${cabinet.pricing.unit-points-per-hour.MEDIUM:25}")
    private long mediumUnit;

    @Value("${cabinet.pricing.unit-points-per-hour.SMALL:15}")
    private long smallUnit;

    /** 单日封顶的小时数（0 = 不按日封顶，回到线性）。 */
    @Value("${cabinet.pricing.daily-cap-hours:12}")
    private int dailyCapHours;

    /** 总计费天数封顶（0 = 不限天数）。 */
    @Value("${cabinet.pricing.cap-days:3}")
    private int capDays;

    /**
     * 远程结束/超窗重开的加收小时数（docs/门态与物品争议设计.md §3）。
     *
     * <p>为什么用“几小时单价”而不是单独一张罚款价目表：它就是计时计费的一个延伸，
     * 随尺寸与策略自动变（小 30 / 中 50 / 大 80 点），账单上一句话能说清；
     * 而单独维护一张价目表就会与计时价漂移（改了小时价忘了改罚款）。
     */
    @Value("${cabinet.pricing.remote-close-hours:2}")
    private int remoteCloseHours;

    public PricingPolicy() {
    }

    /**
     * @param unitPointsPerHour 计费单价（快照后不可变）
     * @param billedHours       <b>实际计费小时</b>（阶梯与封顶之后的结果，不是墙上小时数）
     * @param consumePoints       应消耗点数
     * @param depositPoints       押金点数（每单固定）
     * @param snapshot            写进订单的定价快照（结算的唯一依据）
     */
    public record Quote(long unitPointsPerHour, int billedHours, long consumePoints,
                        long depositPoints, String snapshot) {
    }

    public Quote quote(SizeType size, long minutes) {
        long unit = unitOf(size);
        return quoteWith(unit, minutes);
    }

    /** 结算入口：单价、免费窗口、阶梯与封顶参数全部取自快照，不读当前配置。 */
    public Quote fromSnapshot(String snapshotJson, long actualMinutes) {
        Snap snap = readSnapshot(snapshotJson);
        // 结算用快照里的参数而不是当前配置：否则“运营今日改封顶”会追溯改掉进行中那张单的价格
        return buildQuote(snap.unit(), actualMinutes, snap.freeMinutes(), snap.dailyCapHours(),
                snap.capDays(), snap.depositPoints(), snap.remoteCloseHours());
    }

    private Quote quoteWith(long unit, long minutes) {
        return buildQuote(unit, minutes, freeMinutes, dailyCapHours, capDays, depositPoints, remoteCloseHours);
    }

    /**
     * 这笔单“未关门离开”该加收多少。
     *
     * <p>读快照而不读当前配置，与结算同一理由：改加收不得追溯改变已发生的单。
     *
     * <p><b>旧快照（第 12/13 刀那批单）读不出这个字段时收 0</b>，而不是拿今天的配置补上：
     * 给历史单凭空加一笔它当时不存在的费用，是整份定价约定里最不能做的事。
     */
    public long remoteFeePoints(String snapshotJson) {
        Snap snap = readSnapshot(snapshotJson);
        if (snap.remoteCloseHours() <= 0) {
            return 0L;
        }
        return snap.unit() * snap.remoteCloseHours();
    }

    /**
     * 这张单的计费是否已到总额封顶（逾期看管的收口判据）：再放下去也不会多收一分，
     * 继续每小时续排只会刷日志，不如交人工。
     *
     * <p><b>读不出快照时返回 false（“未到顶”）而不是抛</b>：两处读快照的代价不对等——
     * 看管侧有 stranded-days 硬兜底，判错只是多盯一阵；结算侧收的是真钱，读不出就必须停。
     * 把两处写成同一个“读不到就报错”，反而会让一张脏快照的单没人再看（而不是报错）。
     */
    public boolean isCapped(String snapshotJson, long heldMinutes) {
        try {
            Snap snap = readSnapshot(snapshotJson);
            if (snap.capDays() <= 0) {
                // 旧快照/配了“不限天数”：永远到不了顶，交给 stranded-days 兜底
                return false;
            }
            return billedHours(heldMinutes, snap.freeMinutes()) >= snap.capDays() * HOURS_IN_DAY;
        } catch (RuntimeException unreadable) {
            log.warn("定价快照读不出封顶参数，按“未到顶”继续看管（结算时仍会硬失败）snapshot={}",
                    preview(String.valueOf(snapshotJson)), unreadable);
            return false;
        }
    }

    /** 快照里算得出价格所需的全部输入。 */
    private record Snap(long unit, int freeMinutes, int dailyCapHours, int capDays, long depositPoints,
                        int remoteCloseHours) {
    }

    private Quote buildQuote(long unit, long minutes, int freeMinutes,
                             int dailyCapHours, int capDays, long depositPoints, int remoteCloseHours) {
        int rawHours = billedHours(minutes, freeMinutes);
        int chargeable = chargeableHours(rawHours, dailyCapHours, capDays);
        long consume = chargeable * unit;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("strategy", "per-hour-daily-cap");
        snapshot.put("unitPointsPerHour", unit);
        snapshot.put("freeMinutes", freeMinutes);
        // 阶梯参数必须一起进快照：否则“封顶改了”会追溯改变进行中订单的价格
        snapshot.put("dailyCapHours", dailyCapHours);
        snapshot.put("capDays", capDays);
        // 加收小时数也是价格的一部分：它变了就是改价，同样不得追溯
        snapshot.put("remoteCloseHours", remoteCloseHours);
        snapshot.put("rawHours", rawHours);
        snapshot.put("billedHours", chargeable);
        snapshot.put("depositPoints", depositPoints);
        snapshot.put("formula", "chargeableHours(ceil(max(0, minutes - freeMinutes) / 60), dailyCapHours, capDays) * unit");
        return new Quote(unit, chargeable, consume, depositPoints, write(snapshot));
    }

    /**
     * 阶梯 + 封顶的小时数。写成纯函数是为了能穷举边界（单测直接扫 0..200 小时）。
     *
     * <p>为什么先截天数再算零头：<b>总额封顶的语义是“后面的天不再计费”</b>，
     * 而不是“超出的部分打个折”——后者会需要一个没人能向用户解释的账单。
     */
    static int chargeableHours(int rawHours, int dailyCapHours, int capDays) {
        if (rawHours <= 0) {
            return 0;
        }
        if (dailyCapHours <= 0 && capDays <= 0) {
            // 旧快照语义：纯线性，一天也不截、天数也不限（改价不得追溯已发的单）
            return rawHours;
        }
        // 日封顶超过 24 小时没有意义（一天只有 24 小时），按 24 收敛而不是报错：
        // 配错了顶多“不封顶”，不该让下单整个失败
        int perDay = dailyCapHours > 0 ? Math.min(dailyCapHours, HOURS_IN_DAY) : HOURS_IN_DAY;
        int days = (rawHours + HOURS_IN_DAY - 1) / HOURS_IN_DAY;
        if (capDays > 0 && days > capDays) {
            return capDays * perDay;
        }
        int lastDayHours = rawHours - (days - 1) * HOURS_IN_DAY;
        return (days - 1) * perDay + Math.min(lastDayHours, perDay);
    }

    /** 免费窗口内 0 小时；超出后向上取整（不足一小时按一小时）。 */
    private int billedHours(long minutes, int freeMinutes) {
        long billable = minutes - freeMinutes;
        if (billable <= 0) {
            return 0;
        }
        // 整数向上取整：(billable + 59) / 60。写成浮点再 ceil 会因为精度在边界上飘（60.0000000001 小时）
        return (int) ((billable + HOURS_IN_MINUTES - 1) / HOURS_IN_MINUTES);
    }

    private long unitOf(SizeType size) {
        return switch (size) {
            case LARGE -> largeUnit;
            case MEDIUM -> mediumUnit;
            case SMALL -> smallUnit;
        };
    }

    private Snap readSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单缺少定价快照，无法结算，需人工处理");
        }
        try {
            JsonNode node = JSON.readTree(snapshotJson);
            long unit = requireLong(node, "unitPointsPerHour", snapshotJson);
            // 阶梯与封顶：“缺字段 = 不限”而不是“缺字段 = 按今天的配置”。
            // 默认用当前值会让一张旧单在新配置下被意外封顶（或多收）——那是追溯改价。
            return new Snap(unit,
                    (int) readLong(node, "freeMinutes", this.freeMinutes),
                    (int) readLong(node, "dailyCapHours", 0L),
                    (int) readLong(node, "capDays", 0L),
                    readLong(node, "depositPoints", this.depositPoints),
                    // 缺字段 = 0 加收（旧单当时的价就是没有这一笔），而不是拿今天的配置补
                    (int) readLong(node, "remoteCloseHours", 0L));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR,
                    "定价快照解析失败，需人工处理 snapshot=" + preview(snapshotJson));
        }
    }

    /** 单价这种算钱字段读不出来就必须停：拿 0 当默认值等于白送，比报错危险得多。 */
    private long requireLong(JsonNode node, String field, String snapshotJson) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR,
                    "定价快照缺少 " + field + " 字段，需人工处理 snapshot=" + preview(snapshotJson));
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        // 容错：历史脏数据里单价被写成了字符串（就是上面那个错用容器 mapper 的时期留下的）。
        // 能读出来就该读出来，不该让已经存在的单突然无法结算
        try {
            long parsed = Long.parseLong(value.asText().trim());
            log.warn("定价快照 {} 是字符串而非数字，已按数值读取；请排查写入侧 snapshot={}",
                    field, preview(snapshotJson));
            return parsed;
        } catch (NumberFormatException notNumeric) {
            throw new BizException(ResultCode.SYSTEM_ERROR,
                    "定价快照 " + field + " 无法解析，需人工处理 snapshot=" + preview(snapshotJson));
        }
    }

    /** 可选字段：读不到用默认值（仅用于“旧快照本来就没这个列”而不是算钱的单价）。 */
    private long readLong(JsonNode node, String field, long fallback) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? fallback : value.asLong();
    }

    private String preview(String json) {
        return json.length() <= 400 ? json : json.substring(0, 400) + "…";
    }

    private String write(Map<String, Object> snapshot) {
        try {
            return JSON.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "定价快照序列化失败");
        }
    }
}
