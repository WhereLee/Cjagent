package com.wherelee.cabinet.infrastructure.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQ 延迟档位的量化误差（第 13 刀：三实现对比里 MQ 那一档的硬约束）。
 *
 * <p>rocketmq-spring 2.3.1 的 {@code syncSend(dest, msg, timeout, delayLevel)} 只支持
 * <b>18 个固定档位</b>（1s 5s 10s 30s 1m 2m … 2h），不是任意时刻。所以"用 MQ 做定时"的
 * 真实精度不是配置值，而是<b>档位之间的跳变</b>：想要 31 秒会得到 60 秒。
 *
 * <p>放在 {@code infrastructure.task} 包里是为了能直接调用那个 package-private 的选档函数：
 * <b>被测的是实现细节里的算术，就不该为了让测试好看而把它改成 public</b>。
 */
class MqDelayLevelTest {

    /** 档位表（秒）：与 {@link MqTrigger} 同源，改实现必须同步这里。 */
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
        // 超出最大档位：顶格而不是抛异常，剩下的时间差由兜底轮询收敛
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
        // 就近取整会选 30s（差 1s），但它落在"提前"那一侧——本用例把这个方向锁死，
        // 免得日后有人"为了精度"改成 Math.round
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

            // 刚跨过上一档 1ms：这是该档最坏的时刻，必须被抬到下一档而不是压回上一档
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
        // 最大过冲写死成断言：档位表一改（换 5.x 的任意时刻定时）这条结论就要重估
        // 7200s - (3600s + 1ms)：过冲是“实际减目标”，那 1ms 也算在目标里
        assertEquals(3_599_999L, worstOvershootMs, "最大过冲出现在：" + worstAt);
        assertTrue(worstAt.startsWith("目标 3600s"), "过冲位置变了：" + worstAt);
    }
}
