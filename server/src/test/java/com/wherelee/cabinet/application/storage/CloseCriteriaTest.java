package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.domain.enums.Presence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结束判据的<b>穷举</b>。
 *
 * <p>这个矩阵值得穷举，因为它是整套门态规则里唯一直接决定"收不收钱、放不放行"的一点，
 * 而它的输入只有四个布尔/三值——纯函数写出来之后，穷举比挑几个组合更省事。
 * 集成测试再验一次"这套判据真的被接到了流程上"，两者不可互相替代。
 */
class CloseCriteriaTest {

    @Test
    @DisplayName("门没关：无论有没有物检、无论现场还是远程，都不许结束")
    void openDoorAlwaysBlocks() {
        for (Presence presence : Presence.values()) {
            for (boolean fresh : new boolean[]{true, false}) {
                for (boolean atSite : new boolean[]{true, false}) {
                    CloseCriteria.Verdict v = CloseCriteria.evaluate(false, presence, fresh, atSite);
                    assertEquals(CloseCriteria.Blocker.DOOR_NOT_CLOSED, v.blocker(),
                            "门开着时任何组合都不能放行：presence=" + presence + " fresh=" + fresh + " atSite=" + atSite);
                    assertFalse(v.closable());
                    assertTrue(v.userMessage().contains("门"), "文案必须告诉用户缺的是哪一条：" + v.userMessage());
                }
            }
        }
    }

    @Test
    @DisplayName("检测到有物：现场与远程都拦，且提示下一步能做什么")
    void presentBlocksEverywhere() {
        for (boolean atSite : new boolean[]{true, false}) {
            CloseCriteria.Verdict v = CloseCriteria.evaluate(true, Presence.PRESENT, true, atSite);
            assertEquals(CloseCriteria.Blocker.CONTENT_PRESENT, v.blocker());
            // 光说"柜内有物品"不够，用户会以为死路一条；出口必须写在同一句话里
            assertTrue(v.userMessage().contains("取出") && v.userMessage().contains("放弃"),
                    "文案要给出两条出路：" + v.userMessage());
        }
    }

    @Test
    @DisplayName("门关 + 确认无物：唯一“拿着凭据”的放行组合")
    void closedAndEmptyCloses() {
        CloseCriteria.Verdict v = CloseCriteria.evaluate(true, Presence.ABSENT, true, true);
        assertTrue(v.closable());
        assertTrue(v.evidenceBacked(), "拿到凭据的结束才能让格口回到可售池");
        CloseCriteria.Verdict remote = CloseCriteria.evaluate(true, Presence.ABSENT, true, false);
        assertTrue(remote.closable() && remote.evidenceBacked(),
                "远程结束在凭据齐全时应当被允许，否则远程出口形同虚设");
    }

    @Test
    @DisplayName("测不到柜内：现场不拦人但拿不到凭据（格口必须被锁），远程直接拦")
    void unverifiableNeverYieldsEvidence() {
        // 物检是本设备形态的必备能力，“测不到”只在传感器坏了时发生；
        // 它不该把人的脚钉在柜机前，但也绝对不能当成“柜子是空的”的证据
        CloseCriteria.Verdict onSite = CloseCriteria.evaluate(true, Presence.UNKNOWN, true, true);
        assertTrue(onSite.closable(), "现场不该被一台坏传感器卡死");
        assertFalse(onSite.evidenceBacked(), "没凭据就不是凭据：调用方必须锁格而不是直接放回可售池");
        assertNotNull(onSite.userMessage(), "这种情况必须告知用户“需要现场确认”");

        assertTrue(CloseCriteria.evaluate(true, null, true, true).closable(), "从没测过同样不拦现场");
        assertFalse(CloseCriteria.evaluate(true, null, true, true).evidenceBacked());

        // 人不在现场时，“看不见”不能当成“里面没东西”
        assertEquals(CloseCriteria.Blocker.CONTENT_UNVERIFIED,
                CloseCriteria.evaluate(true, Presence.UNKNOWN, true, false).blocker());
        assertEquals(CloseCriteria.Blocker.CONTENT_UNVERIFIED,
                CloseCriteria.evaluate(true, null, true, false).blocker());
    }

    @Test
    @DisplayName("陈年读数等于没测过：新鲜度不是一个可以忽略的细节")
    void staleEvidenceDegradesToUnverified() {
        assertTrue(CloseCriteria.evaluate(true, Presence.ABSENT, false, true).closable());
        assertFalse(CloseCriteria.evaluate(true, Presence.ABSENT, false, true).evidenceBacked(),
                "超龄的“没东西”不能当凭据：否则一次三天前的读数能替这个格口永远作证");
        assertEquals(CloseCriteria.Blocker.CONTENT_UNVERIFIED,
                CloseCriteria.evaluate(true, Presence.ABSENT, false, false).blocker());
    }

    @Test
    @DisplayName("门的优先级最高：又有物又没关门时，先说门")
    void doorTakesPrecedenceOverPresence() {
        CloseCriteria.Verdict v = CloseCriteria.evaluate(false, Presence.PRESENT, true, true);
        assertEquals(CloseCriteria.Blocker.DOOR_NOT_CLOSED, v.blocker(),
                "同时不满足时报错顺序决定用户下一步做什么：门没关，先关再说别的");
    }
}
