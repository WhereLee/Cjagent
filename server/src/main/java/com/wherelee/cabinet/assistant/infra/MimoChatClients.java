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

@Configuration
@ConditionalOnProperty(name = "cabinet.agent.client", havingValue = "real")
public class MimoChatClients {

    private static final Logger log = LoggerFactory.getLogger(MimoChatClients.class);

    @Value("${cabinet.agent.mimo.base-url:https://api.xiaomimimo.com/v1}")
    private String baseUrl;

    @Value("${cabinet.agent.mimo.api-key:}")
    private String apiKey;

    @Value("${cabinet.agent.mimo.model:mimo-v2.6-flash}")
    private String model;

    @Value("${cabinet.agent.mimo.timeout-ms:20000}")
    private long timeoutMs;

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
        RestClient.Builder restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("api-key", apiKey);
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
