package com.wherelee.cabinet.assistant.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.assistant.api.ItemReviewPort;
import com.wherelee.cabinet.locker.api.Presence;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "cabinet.dispute.review.client", havingValue = "mimo")
public class MimoItemReviewPort implements ItemReviewPort {

    private static final Logger log = LoggerFactory.getLogger(MimoItemReviewPort.class);

    private static final String SYSTEM_PROMPT = """
            你是储物柜格口的巡查员。给你两张同一个格口的照片：第 1 张是运维确认为空柜时拍的基准照，\
            第 2 张是本次关门后的留底照。判断第 2 张里是否出现了基准照中不存在的物品。
            只输出一个 JSON 对象，不要任何解释文字：
            {"presence":"PRESENT|ABSENT|UNKNOWN","confidence":0.0到1.0,"reason":"不超过40字的依据"}
            判定规则：与基准照比对后确实多出物品→PRESENT；两图都空→ABSENT；
            只要光线、角度、遮挡或清晰度不足以可靠比较，就必须回答 UNKNOWN，不要猜。""";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${cabinet.dispute.mimo.base-url:https://api.xiaomimimo.com/v1}")
    private String baseUrl;

    @Value("${cabinet.dispute.mimo.api-key:}")
    private String apiKey;

    @Value("${cabinet.dispute.mimo.model:mimo-v2.6-flash}")
    private String model;

    @Value("${cabinet.dispute.mimo.timeout-ms:20000}")
    private long timeoutMs;

    @Value("${cabinet.dispute.mimo.max-completion-tokens:1200}")
    private int maxCompletionTokens;

    @Value("${cabinet.dispute.mimo.min-confidence:0.6}")
    private BigDecimal minConfidence;

    @PostConstruct
    void requireCredential() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("cabinet.dispute.review.client=mimo 但缺少 api-key："
                    + "请设置环境变量 MIMO_API_KEY（密钥不得写入仓库或配置文件明文）");
        }
    }

    @Override
    public Review review(Request request) {
        if (request.closedPhotoRef() == null || request.closedPhotoRef().isBlank()
                || request.baselinePhotoRef() == null || request.baselinePhotoRef().isBlank()) {
            return Review.unknown("缺少关门留底照或空格基准照，无法比对（基准照由业务运维清柜时采集）");
        }
        try {
            return withThreshold(call(request));
        } catch (Exception e) {
            log.warn("AI 复审失败，按无法判断处理 slot={}：{}", request.compartmentId(), e.toString());
            return Review.unknown("AI 调用失败：" + e.getClass().getSimpleName());
        }
    }

    private Review call(Request request) throws Exception {
        String body = JSON.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "text", "text", "第1张为基准空柜照，第2张为当前照，只返回 JSON。"),
                                Map.of("type", "image_url", "image_url",
                                        Map.of("url", toDataUri(request.baselinePhotoRef()))),
                                Map.of("type", "image_url", "image_url",
                                        Map.of("url", toDataUri(request.closedPhotoRef())))))),
                "max_completion_tokens", maxCompletionTokens,
                "temperature", 0.0,
                "stream", false));
        HttpRequest httpReq = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " " + trim(resp.body(), 160));
        }
        JsonNode root = JSON.readTree(resp.body());
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        return parse(content);
    }

    static Review parse(String content) {
        if (content == null || content.isBlank()) {
            return Review.unknown("模型返回空正文（疑似思考 token 占满预算）");
        }
        int from = content.indexOf('{');
        int to = content.lastIndexOf('}');
        if (from < 0 || to <= from) {
            return Review.unknown("模型未返回 JSON：" + trim(content, 60));
        }
        try {
            JsonNode node = JSON.readTree(content.substring(from, to + 1));
            String raw = node.path("presence").asText("").trim().toUpperCase(java.util.Locale.ROOT);
            if (!"PRESENT".equals(raw) && !"ABSENT".equals(raw) && !"UNKNOWN".equals(raw)) {
                return Review.unknown("结论字段不可识别：" + trim(raw, 24));
            }
            Presence presence = Presence.valueOf(raw);
            BigDecimal confidence = node.path("confidence").isNumber()
                    ? node.path("confidence").decimalValue()
                    : parseDecimal(node.path("confidence").asText(""));
            String reason = trim(node.path("reason").asText(""), 200);
            return new Review(presence, confidence, reason.isEmpty() ? null : reason);
        } catch (Exception malformed) {
            return Review.unknown("模型返回无法解析：" + trim(content, 60));
        }
    }

    Review withThreshold(Review raw) {
        if (raw.presence() != null && raw.presence() != Presence.UNKNOWN
                && (raw.confidence() == null || raw.confidence().compareTo(minConfidence) < 0)) {
            return new Review(Presence.UNKNOWN, raw.confidence(),
                    "置信度低于阈值 " + minConfidence + "（或未给置信度），按无法判断处理");
        }
        return raw;
    }

    private static BigDecimal parseDecimal(String text) {
        try {
            return new BigDecimal(text.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String toDataUri(String ref) throws Exception {
        if (ref.startsWith("data:") || ref.startsWith("http://") || ref.startsWith("https://")) {
            return ref;
        }
        Path path = Path.of(ref);
        byte[] bytes = Files.readAllBytes(path);
        return "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
