package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/**
 * 订单结束原因（{@code biz_storage_order.close_reason}）。
 *
 * <p>为什么必须分开记：四种结束方式在<b>账单</b>和<b>格口后续</b>上都不一样，
 * 而只记"已关闭"就看不出区别。真实纠纷里第一个问题永远是"这单是怎么结的"，
 * 答案决定要不要收加收、格口该不该清、能不能免除费用。
 *
 * <p>特别地，{@link #ABANDONED} 是这套规则里唯一允许"柜内还有东西却停止计费"的口子，
 * 所以它必须留下明确的痕迹——同时格口会转 {@link CompartmentAnomaly#CONTENT_LEFT}，
 * 在业务运维清走那件东西之前不能卖给别人（不变量 I12）。
 */
public enum OrderCloseReason {

    /** 正常：门已关且柜内无物 */
    NORMAL,
    /** 用户声明"里面的东西不要了"，强制结束（格口转遗留物异常） */
    ABANDONED,
    /** 远程结束：人不在现场，按该格口 {@code remote-close-hours} 小时单价加收 */
    REMOTE,
    /** 争议判为设备误报，免除争议期间产生的计费（客服/AI 结论，13C 接入） */
    DISPUTE_WAIVED;

    /** 这次结束要不要收加收。只有远程结束收——声明放弃的人已经付了计时费且损失了自己的物品。 */
    public boolean chargesPenalty() {
        return this == REMOTE;
    }

    /** 这次结束后格口要不要标异常（不能直接回可售池）。 */
    public boolean leavesAnomaly() {
        return this == ABANDONED;
    }

    public static OrderCloseReason of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
