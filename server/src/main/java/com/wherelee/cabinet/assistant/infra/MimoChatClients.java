package com.wherelee.cabinet.assistant.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * MiMo ChatClient 的共享装配（第 23 刀）：real 模式下所有 agent 共用一个客户端。
 *
 * <p>第 22 刀的认证、参数与 baseUrl 收敛逻辑原本长在 {@link MimoSupportAgent} 里；
 * 第 23 刀的简报 agent 也要同一套，各抄一遍就一定各漂一次（404 双重拼接就是抄写漂移的形状）。
 * 收敛到这里一处说清：<b>配置缺失是装配问题</b>（缺 key 启动期失败）、
 * <b>认证一处声明</b>（api-key 头 + Bearer 并存）、<b>路径一处收敛</b>（/v1 只出现一次）。
 *
 * <p>stub 模式下本装配不生效，各 stub agent 各自为政，行为不变。
 */
@Configuration
@ConditionalOnProperty(name = "cabinet.agent.client", havingValue = "real")
public class MimoChatClients {

    private static final Logger log = LoggerFactory.getLogger(MimoChatClients.class);

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

    @Bean
    ChatClient mimoChatClient() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("cabinet.agent.client=real 但缺少 api-key："
                    + "请设置环境变量 MIMO_API_KEY（密钥不得写入仓库或配置文件明文）");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeoutMs);
        requestFactory.setReadTimeout((int) timeoutMs);
        // MiMo 认的是 api-key 头（13C 实测）；Spring AI 默认另发 Bearer，两者并存不冲突
        RestClient.Builder restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("api-key", apiKey);
        // base-url 沿用 13C 的写法（带 /v1），而 Spring AI 的 completionsPath 默认自带 /v1 前缀——
        // 直接拼会变成 /v1/v1/...（22 刀联调实测 404），这里统一把 baseUrl 收敛到域名根
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
        log.info("MiMo ChatClient 已就绪：model={} timeout={}ms", model, timeoutMs);
        return ChatClient.builder(chatModel).build();
    }
}
