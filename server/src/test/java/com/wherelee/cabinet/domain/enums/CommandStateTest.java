package com.wherelee.cabinet.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指令状态机单测（纯领域，不连库）。
 *
 * <p>注意：<b>终态不得有出边</b>这条规则本身在枚举静态块里自检（写错就直接 {@code ExceptionInInitializerError}），
 * 所以这里不需要再造一个"验证自检会抛"的假测试，只需断言行为符合语义。
 */
class CommandStateTest {

    @Test
    @DisplayName("只有 SUCCEEDED 是终态；失败与超时都能重试")
    void terminalAndRetriableSets() {
        assertTrue(CommandState.SUCCEEDED.isTerminal());
        assertFalse(CommandState.FAILED.isTerminal(), "失败可重试，不是终态");
        assertFalse(CommandState.TIMEOUT.isTerminal(), "超时可重试，不是终态");

        assertTrue(CommandState.FAILED.isRetriable());
        assertTrue(CommandState.TIMEOUT.isRetriable());
        assertFalse(CommandState.SUCCEEDED.isRetriable());
        assertFalse(CommandState.SENT.isRetriable());
    }

    @Test
    @DisplayName("超时后晚到的成功必须能落地")
    void lateSuccessCanCorrectTimeout() {
        assertTrue(CommandState.TIMEOUT.canTransitTo(CommandState.SUCCEEDED),
                "设备其实开了门、只是回执晚到：不修正就会出现\"件在里面、系统以为没开\"");
        assertTrue(CommandState.TIMEOUT.canTransitTo(CommandState.SENT), "允许重新下发");
        assertFalse(CommandState.SUCCEEDED.canTransitTo(CommandState.TIMEOUT), "终态不回退");
    }

    @Test
    @DisplayName("不允许自迁移，也不允许 PENDING 跳过 SENT 直接成功")
    void noSelfTransitionAndNoSkip() {
        for (CommandState state : CommandState.values()) {
            assertFalse(state.canTransitTo(state), state + " 不允许迁移到自己");
        }
        assertFalse(CommandState.PENDING.canTransitTo(CommandState.SUCCEEDED));
        assertTrue(CommandState.PENDING.canTransitTo(CommandState.SENT));
    }
}
