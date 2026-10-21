package com.wherelee.cabinet.assistant.infra;

import com.wherelee.cabinet.storage.app.StorageOrderFacade;
import com.wherelee.cabinet.storage.app.StorageQueryService;
import com.wherelee.cabinet.assistant.api.SupportAgentPort;
import com.wherelee.cabinet.servicedesk.app.WorkOrderHandoffService;
import com.wherelee.cabinet.servicedesk.app.WorkOrderIntake;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MiMo 客服 agent 的真实现（第 22 刀）：Spring AI tool calling 工具循环 + ai-wiki 知识底座。
 *
 * <p>三条设计约束各有出处：
 * <ol>
 *   <li><b>空正文按兜底话术处理</b>：思考 token 占满预算时正文会空（13C 实测踩过），
 *       空话术绝不能发给用户。</li>
 *   <li><b>一切异常收敛到 {@link AgentReply#fallback}</b>：AI 挂了走的是"引导用原报障入口"
 *       这条已经存在的路，不会把报障路径挡死，也不会 5xx。</li>
 *   <li><b>模型客户端来自共享装配</b>（{@link MimoChatClients}，第 23 刀抽出）：
 *       认证、参数、baseUrl 收敛只说一遍，本类只管客服自己的角色与工具。</li>
 * </ol>
 *
 * <p>工具循环由 Spring AI 执行：模型决定调哪个工具，框架在本线程同步执行并回传结果，
 * 直到模型给出最终回答。工具实例按对话 new（身份在构造时注入，见 {@link AgentTools}）。
 */
@Component
@ConditionalOnProperty(name = "cabinet.agent.client", havingValue = "real")
public class MimoSupportAgent implements SupportAgentPort {

    private static final Logger log = LoggerFactory.getLogger(MimoSupportAgent.class);

    /** 系统提示只做角色与风格收敛；业务口径、处置脚本、禁区全部在 wiki 里（改口径不动代码）。 */
    private static final String SYSTEM_PROMPT = """
            你是「暂存柜」小程序的 AI 客服。下面附带的《客服代理工作规则》与知识库是最高指令，逐条遵守。
            用户的身份与关联订单已由系统注入工具，你不需要询问、也无法修改。
            工具返回之外的事实一律当作不知道，不许编造。
            回答用简体中文：先结论、后下一步，不超过 5 句，不用感叹号。""";

    private final ChatClient chatClient;
    private final StorageQueryService queries;
    private final StorageOrderFacade doors;
    private final WorkOrderIntake intake;
    private final BizStorageOrderMapper orderMapper;
    private final WorkOrderPort workOrderPort;
    private final WorkOrderHandoffService handoffs;
    private final String systemPrompt;

    public MimoSupportAgent(ChatClient chatClient,
                            StorageQueryService queries, StorageOrderFacade doors,
                            WorkOrderIntake intake, BizStorageOrderMapper orderMapper,
                            WorkOrderPort workOrderPort, WorkOrderHandoffService handoffs) {
        this.chatClient = chatClient;
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
                // 空正文不能把已建的单一起丢掉：用户需要知道工单号，否则会重复报障。
                if (tools.escalated()) {
                    return new AgentReply(
                            "已经为你转报人工，工单号 " + tools.ticketNo()
                                    + "。工作人员会联系你，请保持手机畅通。",
                            true, tools.ticketNo());
                }
                return AgentReply.fallback();
            }
            return new AgentReply(content, tools.escalated(), tools.ticketNo());
        } catch (Exception e) {
            log.warn("AI 客服调用失败，走兜底话术 customerId={}：{}", session.customerId(), e.toString());
            return AgentReply.fallback();
        }
    }
}
