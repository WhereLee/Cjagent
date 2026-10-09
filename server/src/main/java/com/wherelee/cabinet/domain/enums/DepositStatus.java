package com.wherelee.cabinet.domain.enums;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 押金状态（S-03：每单固定点数，与消费点数互不混用）。
 *
 * <p>押金与点数的区别不是金额，而是<b>义务</b>：点数是"预付的消耗"，押金是"必须原样还回来的东西"。
 * 所以两者分栏记账，押金退还不能靠"再充点数"糊过去。
 *
 * <p>{@link #FAILED} 不是死端：退款失败要进"未退清单"由调度重试（第 13 刀），
 * 用户已离场的押金不能变成平台的隐形收入——那是不变式 4 要盯的悬挂。
 */
public enum DepositStatus {

    HELD,
    REFUNDING,
    REFUNDED,
    FAILED;

    private static final Set<DepositStatus> TERMINAL = EnumSet.of(REFUNDED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public static DepositStatus of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
