package com.wherelee.cabinet.application.support;

/**
 * C 端智能客服的端口（第 22 刀）。
 *
 * <p>用户遇到简单业务问题先由 AI 应答：能自助的替他做（查自己的单、重开一次门），
 * 解决不了的由 AI 依判断转报工单。AI 在这里的全部合法性来自一句话：
 * <b>它只替用户本人执行用户授权的动作，拿不准就升级给人</b>——
 * 所以它不判定 RESOLVED、不派线下、不碰钱，那些仍是人的决策。
 *
 * <p>接口只谈业务语义，不出现任何模型/框架类型：实现是桩还是 MiMo（Spring AI tool calling）
 * 由 infrastructure 决定，换模型或换框架不得外溢到这一层。
 */
public interface SupportAgentPort {

    /**
     * 处理一次客服对话。
     *
     * <p>无状态单轮：会话记忆不做（本刀口径），上下文靠 session 里的订单号与消息本身。
     * 实现必须自行兜住一切失败——AI 不可用返回兜底话术，绝不能把异常抛成 5xx 挡住报障路径。
     */
    AgentReply chat(SupportSession session);

    /**
     * 一次对话的输入。<b>customerId 由登录态注入</b>，来自端点层的
     * {@code VerifiedToken}，永远不接受模型或请求体传身份。
     * {@code orderNo} 可空：用户从订单详情进来会带上，从客服入口进来则为空。
     */
    record SupportSession(Long customerId, String orderNo, String message) {
    }

    /**
     * 一次对话的输出。
     *
     * @param reply     给用户看的话术
     * @param escalated 本次对话是否已转报工单（前端据此把入口切到"已建单"状态）
     * @param ticketNo  转报的工单 ID（escalated 时非空）
     * @param degraded  本次没有真正的 AI 参与（不可用/未开启/空响应）——不计入自助解决率
     */
    record AgentReply(String reply, boolean escalated, String ticketNo, boolean degraded) {

        /** 兼容旧调用：普通应答默认不是兜底。 */
        public AgentReply(String reply, boolean escalated, String ticketNo) {
            this(reply, escalated, ticketNo, false);
        }

        /** AI 不可用（超时/异常/空响应）时的兜底话术：引导走真实存在的入口，绝不 5xx。 */
        public static AgentReply fallback() {
            return new AgentReply(
                    "AI 客服暂时无法应答。你可以在「我的寄存单 - 订单详情」里使用开柜、上报遗留物，"
                            + "或直接联系现场工作人员；也可以稍后再来找我。",
                    false, null, true);
        }

        /** 桩实现的话术：AI 客服未开启时的固定引导（CI 与集成测试跑的就是它）。 */
        public static AgentReply stub() {
            return new AgentReply(
                    "AI 客服当前未开启。你可以在「我的寄存单 - 订单详情」里使用开柜、上报遗留物，"
                            + "或直接联系现场工作人员。",
                    false, null, true);
        }
    }
}
