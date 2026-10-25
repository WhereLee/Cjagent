package com.wherelee.cabinet.platform.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqDelayLevelTest {

    private static final long[] LEVEL_SECONDS = {
            1L, 5L, 10L, 30L, 60L, 120L, 180L, 240L, 300L, 360L, 420L, 480L, 540L, 600L,
            1200L, 1800L, 3600L, 7200L};

    @Test
    @DisplayName("档位选择：不早于目标延迟的最小档（向上取整）")
    void picksLevelNotEarlierThanTarget() {
        assertEquals(1, MqTrigger.delayLevelAtLeast(0L), "立刻也要有一个合法档位，不能给 0");
        assertEquals(1, MqTrigger.delayLevelAtLeast(1_000L));
        assertEquals(2, MqTrigger.delayLevelAtLeast(1_001L), "1.001 秒已超出档位 1，只能上档位 2");
        assertEquals(2, MqTrigger.delayLevelAtLeast(5_000L), "正好等于档位值时不该多等一档");
        assertEquals(3, MqTrigger.delayLevelAtLeast(5_001L));
        assertEquals(4, MqTrigger.delayLevelAtLeast(30_000L));
        assertEquals(5, MqTrigger.delayLevelAtLeast(30_001L), "31 秒会得到 60 秒，这就是 MQ 的精度");
        assertEquals(LEVEL_SECONDS.length, MqTrigger.delayLevelAtLeast(86_400_000L));
    }

    @Test
    @DisplayName("为什么向上取整而不是就近：提前触发会误释放，晚触发只是多等")
    void roundingDirectionIsAsymmetric() {
        long wantMs = 31_000L;
        int level = MqTrigger.delayLevelAtLeast(wantMs);
        long actualMs = LEVEL_SECONDS[level - 1] * 1000L;

        assertTrue(actualMs >= wantMs,
                "实际触发不得早于业务要求的时刻：早触发=把还在用的格口判成超时");
        assertEquals(60_000L, actualMs, "31s 的目标必须被抬到 60s，而不是压到 30s");
    }

    @Test
    @DisplayName("全表扫描：每个档位边界都不提前，最大过冲出现在 1h→2h 那一跳")
    void noLevelEverFiresEarly() {
        long worstOvershootMs = 0L;
        String worstAt = "";

        for (int level = 1; level <= LEVEL_SECONDS.length; level++) {
            long exact = LEVEL_SECONDS[level - 1] * 1000L;
            assertEquals(level, MqTrigger.delayLevelAtLeast(exact),
                    "档位值本身必须落在自己那一档：" + exact / 1000 + "s");

            if (level > 1) {
                long justAfterPrevious = LEVEL_SECONDS[level - 2] * 1000L + 1L;
                int chosen = MqTrigger.delayLevelAtLeast(justAfterPrevious);
                long actual = LEVEL_SECONDS[chosen - 1] * 1000L;
                assertTrue(actual >= justAfterPrevious,
                        justAfterPrevious + "ms 被提前到 " + actual + "ms（方向性错误）");
                long overshoot = actual - justAfterPrevious;
                if (overshoot > worstOvershootMs) {
                    worstOvershootMs = overshoot;
                    worstAt = "目标 " + justAfterPrevious / 1000 + "s → 实得 " + actual / 1000 + "s";
                }
            }
        }
        assertEquals(3_599_999L, worstOvershootMs, "最大过冲出现在：" + worstAt);
        assertTrue(worstAt.startsWith("目标 3600s"), "过冲位置变了：" + worstAt);
    }
}
