package com.wherelee.cabinet.servicedesk.api;

/**
 * 交接事实（AI 尝试过什么、用户说了什么）的生成入口。
 *
 * <p>它被 assistant 用来把一次失败尝试写进工单；读取仍由工单侧负责，
 * 调用方拿到的是一段可落库的 JSON 文本，不需要知道内部结构。
 */
public interface WorkOrderHandoffPort {

    String aiReport(String orderNo, String userStatement, String attempt, Boolean userOnSite,
                    WorkOrderIntent intent, String traceId);
}
