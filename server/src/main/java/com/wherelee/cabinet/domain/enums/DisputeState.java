package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/**
 * 柜内物品争议的状态（{@code biz_storage_order.dispute_state}）。
 *
 * <p>它存在的理由只有一个：把"用户说没有、系统说有"这件事的**进度**记下来，
 * 从而能算出两件事——争议是从什么时候开始的（误报免除时要把计费终点退回去），
 * 以及这单该由谁收尾（自助 / AI / 人工）。没有这个状态，免除费用就没有依据。
 *
 * <p>注意它<b>不是</b>订单状态：订单还在 ACTIVE 正常计费，争议是挂在订单上的一层附加事实。
 * 把它做成新的 OrderStatus 会污染状态机（每个状态都要回答"能不能取件"），
 * 而争议中的单本来就照常计费、照常可以被用户结束。
 */
public enum DisputeState {

    /** 结束被物检拦下，用户否认柜内有他的物品 */
    ITEM_DISPUTED,
    /** 已做过一次 AI 看图复审（结论可能放行、可能仍判有物） */
    AI_REVIEWED,
    /** 复审次数用完或 AI 说"无法判断"，转真人客服 */
    HUMAN_REVIEW;

    public static DisputeState of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
