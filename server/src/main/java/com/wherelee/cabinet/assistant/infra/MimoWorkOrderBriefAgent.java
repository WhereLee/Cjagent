package com.wherelee.cabinet.assistant.infra;

import com.wherelee.cabinet.assistant.api.WorkOrderBriefPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MiMo 工单简报 agent 的真实现（第 23 刀）：带只读工具的结构化归纳。
 *
 * <p>与客服 agent 的两点不同：
 * <ol>
 *   <li><b>输出是结构体不是话术</b>：{@code .entity(Brief.class)} 让模型按
 *       summary/draftNote/similarCases 的 JSON Schema 作答并自动解析回填——
 *       一次工具循环生成三件，不手撕 JSON。</li>
 *   <li><b>工具全只读</b>：归纳任务的输入是明确的（这张单的流水/证据/历史），
 *       不需要写权限——模型想自动关单在代码层面就不可能。</li>
 * </ol>
 *
 * <p>失败路径统一收敛到 {@link Brief#fallback}：空输出（思考 token 占满，13C 实测）、
 * 解析失败、超时全部一样处理，业务员永远拿到可展示的简报或明确的话术，不会 5xx。
 */
@Component
@ConditionalOnProperty(name = "cabinet.agent.client", havingValue = "real")
public class MimoWorkOrderBriefAgent implements WorkOrderBriefPort {

    private static final Logger log = LoggerFactory.getLogger(MimoWorkOrderBriefAgent.class);

    /** 角色与边界收敛；事实与口径在 wiki（改口径不动代码）。 */
    private static final String SYSTEM_PROMPT = """
            你是储物柜运营后台的工单简报助手。下面附带的《业务员简报助手工作规则》与知识库是最高指令，逐条遵守。
            先用工具把工单上下文、事件流水、证据时间线、相似已结单都看一遍，再按规则产出简报三件。
            只输出规则要求的简报内容，不要寒暄。""";

    private final ChatClient chatClient;
    private final WorkOrderConsolePort workOrders;
    private final String systemPrompt;

    public MimoWorkOrderBriefAgent(ChatClient chatClient, WorkOrderConsolePort workOrders) {
        this.chatClient = chatClient;
        this.workOrders = workOrders;
        this.systemPrompt = SYSTEM_PROMPT + "\n\n" + WikiLoader.wiki();
        log.info("AI 工单简报已就绪：wiki={} 字符", systemPrompt.length());
    }

    @Override
    public Brief brief(Long workOrderId, Long operatorId) {
        try {
            BriefTools tools = new BriefTools(workOrders, workOrderId);
            Brief brief = chatClient.prompt()
                    .system(systemPrompt)
                    .user("请为工单 " + workOrderId + " 生成简报。")
                    .tools(tools)
                    .call()
                    .entity(Brief.class);
            if (brief == null || brief.summary() == null || brief.summary().isBlank()) {
                log.warn("AI 简报返回空内容（疑似思考 token 占满预算），走兜底 workOrderId={}", workOrderId);
                return Brief.fallback();
            }
            return brief;
        } catch (Exception e) {
            log.warn("AI 简报生成失败，走兜底 workOrderId={}：{}", workOrderId, e.toString());
            return Brief.fallback();
        }
    }
}
