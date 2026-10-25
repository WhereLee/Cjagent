package com.wherelee.cabinet.assistant.infra;

import com.wherelee.cabinet.storage.api.StorageOrderCommandPort;
import com.wherelee.cabinet.storage.api.StorageQueryPort;
import com.wherelee.cabinet.assistant.api.SupportAgentPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderHandoffPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntakePort;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cabinet.agent.client", havingValue = "real")
public class MimoSupportAgent implements SupportAgentPort {

    private static final Logger log = LoggerFactory.getLogger(MimoSupportAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是「暂存柜」小程序的 AI 客服。下面附带的《客服代理工作规则》与知识库是最高指令，逐条遵守。
            用户的身份与关联订单已由系统注入工具，你不需要询问、也无法修改。
            工具返回之外的事实一律当作不知道，不许编造。
            回答用简体中文：先结论、后下一步，不超过 5 句，不用感叹号。""";

    private final ChatClient chatClient;
    private final AgentBulkhead bulkhead;
    private final StorageQueryPort queries;
    private final StorageOrderCommandPort doors;
    private final WorkOrderIntakePort intake;
    private final BizStorageOrderMapper orderMapper;
    private final WorkOrderPort workOrderPort;
    private final WorkOrderHandoffPort handoffs;
    private final String systemPrompt;

    public MimoSupportAgent(ChatClient chatClient, AgentBulkhead bulkhead,
                            StorageQueryPort queries, StorageOrderCommandPort doors,
                            WorkOrderIntakePort intake, BizStorageOrderMapper orderMapper,
                            WorkOrderPort workOrderPort, WorkOrderHandoffPort handoffs) {
        this.chatClient = chatClient;
        this.bulkhead = bulkhead;
        this.queries = queries;
        this.doors = doors;
        this.intake = intake;
        this.orderMapper = orderMapper;
        this.workOrderPort = workOrderPort;
        this.handoffs = handoffs;
        this.systemPrompt = SYSTEM_PROMPT + "\n\n" + WikiLoader.wiki();
        log.info("AI 客服已就绪：wiki={} 字符", systemPrompt.length());
    }

    @Override
    public AgentReply chat(SupportSession session) {
        try {
            return bulkhead.call(() -> doChat(session));
        } catch (Exception e) {
            log.warn("AI 客服调用失败，走兜底话术 customerId={}：{}", session.customerId(), e.toString());
            return AgentReply.fallback();
        }
    }

    private AgentReply doChat(SupportSession session) {
        AgentTools tools = new AgentTools(queries, doors, intake, orderMapper, workOrderPort,
                handoffs, session);
        String user = "用户消息：" + session.message()
                + "\n关联订单号：" + (session.orderNo() == null || session.orderNo().isBlank()
                ? "无" : session.orderNo());
        String content = chatClient.prompt()
                .system(systemPrompt)
                .user(user)
                .tools(tools)
                .call()
                .content();
        if (content == null || content.isBlank()) {
            log.warn("AI 客服返回空正文（疑似思考 token 占满预算），escalated={} customerId={}",
                    tools.escalated(), session.customerId());
            if (tools.escalated()) {
                return new AgentReply(
                        "已经为你转报人工，工单号 " + tools.ticketNo()
                                + "。工作人员会联系你，请保持手机畅通。",
                        true, tools.ticketNo());
            }
            return AgentReply.fallback();
        }
        return new AgentReply(content, tools.escalated(), tools.ticketNo());
    }
}
