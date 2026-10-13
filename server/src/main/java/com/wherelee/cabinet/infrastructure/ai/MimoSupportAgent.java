package com.wherelee.cabinet.infrastructure.ai;

import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import com.wherelee.cabinet.application.storage.StorageQueryService;
import com.wherelee.cabinet.application.support.SupportAgentPort;
import com.wherelee.cabinet.application.workorder.WorkOrderIntake;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * MiMo 客服 agent 的真实现（第 22 刀）：Spring AI tool calling 工具循环 + ai-wiki 知识底座。
 *
 * <p>三条设计约束各有出处：
 * <ol>
 *   <li><b>认证头用 api-key 而不是只靠 Bearer</b>：MiMo 兼容端点认这个头（13C 识图实测），
 *       Spring AI 默认发 Bearer——两个头并存，谁被认算谁的，实测时再收敛成一个。</li>
 *   <li><b>空正文按兜底话术处理</b>：思考 token 占满预算时正文会空（13C 实测踩过），
 *       空话术绝不能发给用户。</li>
 *   <li><b>一切异常收敛到 {@link AgentReply#fallback}</b>：AI 挂了走的是"引导用原报障入口"
 *       这条已经存在的路，不会把报障路径挡死，也不会 5xx。</li>
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

    private final StorageQueryService queries;
    private final StorageOrderFacade doors;
    private final WorkOrderIntake intake;
    private final BizStorageOrderMapper orderMapper;

    @Value("${cabinet.agent.mimo.base-url:https://api.xiaomimimo.com/v1}")
    private String baseUrl;

    /** 只从环境变量注入：密钥不进仓库、不进日志。 */
    @Value("${cabinet.agent.mimo.api-key:}")
    private String apiKey;

    @Value("${cabinet.agent.mimo.model:mimo-v2.6-flash}")
    private String model;

    @Value("${cabinet.agent.mimo.timeout-ms:20000}")
    private long timeoutMs;

    /** 工具循环每一轮都花思考+正文 token：给小了正文会空（13C 实测）。 */
    @Value("${cabinet.agent.mimo.max-completion-tokens:1500}")
    private int maxCompletionTokens;

    private ChatClient chatClient;
    private String systemPrompt;

    public MimoSupportAgent(StorageQueryService queries, StorageOrderFacade doors,
                            WorkOrderIntake intake, BizStorageOrderMapper orderMapper) {
        this.queries = queries;
        this.doors = doors;
        this.intake = intake;
        this.orderMapper = orderMapper;
    }

    /**
     * 配了 real 却没给 key，启动期就失败（与支付通道、识图同一取向：
     * 配置缺失是装配问题，不该由业务运行时代偿）。
     */
    @PostConstruct
    void init() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("cabinet.agent.client=real 但缺少 api-key："
                    + "请设置环境变量 MIMO_API_KEY（密钥不得写入仓库或配置文件明文）");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeoutMs);
        requestFactory.setReadTimeout((int) timeoutMs);
        RestClient.Builder restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("api-key", apiKey);
        // MiMo 认的是 api-key 头（13C 实测）；Spring AI 默认另发 Bearer，两者并存不冲突。
        // base-url 沿用 13C 的写法（带 /v1），而 Spring AI 的 completionsPath 默认自带 /v1 前缀——
        // 直接拼会变成 /v1/v1/...（联调实测 404），这里统一把 baseUrl 收敛到域名根
        String root = baseUrl.endsWith("/v1") ? baseUrl.substring(0, baseUrl.length() - 3) : baseUrl;
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(root)
                .apiKey(apiKey)
                .restClientBuilder(restClient)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .maxTokens(maxCompletionTokens)
                        .temperature(0.2)
                        .build())
                .build();
        this.chatClient = ChatClient.builder(chatModel).build();
        this.systemPrompt = SYSTEM_PROMPT + "\n\n" + WikiLoader.wiki();
        log.info("AI 客服已就绪：model={} wiki={} 字符", model, systemPrompt.length());
    }

    @Override
    public AgentReply chat(SupportSession session) {
        try {
            AgentTools tools = new AgentTools(queries, doors, intake, orderMapper, session);
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
                log.warn("AI 客服返回空正文（疑似思考 token 占满预算），走兜底话术 customerId={}",
                        session.customerId());
                return AgentReply.fallback();
            }
            return new AgentReply(content, tools.escalated(), tools.ticketNo());
        } catch (Exception e) {
            log.warn("AI 客服调用失败，走兜底话术 customerId={}：{}", session.customerId(), e.toString());
            return AgentReply.fallback();
        }
    }
}
