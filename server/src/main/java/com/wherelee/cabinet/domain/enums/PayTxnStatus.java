package com.wherelee.cabinet.domain.enums;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 通道流水状态（本地视角的"这笔钱收到没有"）。
 *
 * <p>它与点数流水是<b>两本账</b>：这里的 PAID 表示通道确认收款，点数侧的 RECHARGE 表示
 * 我们真的给用户加了点数。两者都成立才是"充值成功"，只成立一边就是对账要抓的差异：
 * <ul>
 *   <li>这里 PAID、点数无入账 → 用户付了钱没拿到点数（资损方向是用户，最恶性）；</li>
 *   <li>点数已入账、这里没有 PAID → 凭空造点（资损方向是平台）。</li>
 * </ul>
 *
 * <p>{@link #REFUNDED} 目前只是预留：本项目的充值不做原路退款（点数可花可退押金，
 * 但充值退款属于真实通道能力，随 S-11 一起接）。留状态而不是等要用的时候改枚举——
 * 状态值一旦进了数据库历史行，改语义比加值难得多。
 */
public enum PayTxnStatus {

    /** 已向通道下单，尚未确认收款 */
    CREATED,
    /** 通道确认收款（可以对用户入账） */
    PAID,
    /** 通道明确失败 */
    FAILED,
    /** 已原路退回（预留） */
    REFUNDED;

    private static final Set<PayTxnStatus> TERMINAL = EnumSet.of(PAID, FAILED, REFUNDED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public static PayTxnStatus of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
