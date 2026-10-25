package com.wherelee.cabinet.assistant.api;

public interface SupportAgentPort {

    AgentReply chat(SupportSession session);

    record SupportSession(Long customerId, String orderNo, String message) {
    }

    record AgentReply(String reply, boolean escalated, String ticketNo, boolean degraded) {

        public AgentReply(String reply, boolean escalated, String ticketNo) {
            this(reply, escalated, ticketNo, false);
        }

        public static AgentReply fallback() {
            return new AgentReply(
                    "AI 客服暂时无法应答。你可以在「我的寄存单 - 订单详情」里使用开柜、上报遗留物，"
                            + "或直接联系现场工作人员；也可以稍后再来找我。",
                    false, null, true);
        }

        public static AgentReply stub() {
            return new AgentReply(
                    "AI 客服当前未开启。你可以在「我的寄存单 - 订单详情」里使用开柜、上报遗留物，"
                            + "或直接联系现场工作人员。",
                    false, null, true);
        }
    }
}
