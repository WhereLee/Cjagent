package com.wherelee.cabinet.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.application.storage.ItemReviewPort;
import com.wherelee.cabinet.domain.enums.Presence;
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

/**
 * MiMo 识图复审的真实现（OpenAI 兼容 chat/completions，模型支持全模态理解）。
 *
 * <p>三条设计约束都来自实测，不是照抄文档：
 * <ol>
 *   <li><b>思考 token 与正文共用 {@code max_completion_tokens}</b>。第一次实测给了 300，
 *       模型把 300 全花在 reasoning 上，<b>content 直接返回空</b>。所以预算给到 1200 起，
 *       且空响应一律按 UNKNOWN 处理——"没内容"绝不能被读成"没东西"。</li>
 *   <li><b>低置信度按 UNKNOWN 处理</b>（阈值可配）。模型会用很肯定的语气说不确定的事，
 *       拿它当凭据就会把别人的行李卖出去或把空柜锁死。</li>
 *   <li><b>没有基准照片就不发请求</b>。只有一张"里面有阴影"的图，模型无从判断那是遗留物还是
 *       内衬反光——它会硬答一个答案。省一次钱是小事，凭空造凭据是大事。</li>
 * </ol>
 *
 * <p>失败路径统一收敛到 {@link Review#unknown}：超时、非 2xx、JSON 解析失败、字段缺失全部一样处理。
 * 这样"AI 挂了"自动走的是<b>转人工</b>那条已经存在的分支，不需要额外代码，也不会默默放行。
 */
@Component
@ConditionalOnProperty(name = "cabinet.dispute.review.client", havingValue = "mimo")
public class MimoItemReviewPort implements ItemReviewPort {

    private static final Logger log = LoggerFactory.getLogger(MimoItemReviewPort.class);

    /**
     * 判定提示词。<b>要求"只输出 JSON"</b>并给出三值枚举，同时把"不可靠就答 UNKNOWN"写进指令：
     * 不给这条退路，模型会在糊图上硬选一边。
     */
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

    /** 只从环境变量注入：密钥不进仓库、不进日志（配置注释里也写了这条）。 */
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

    /**
     * 开了 mimo 却没给 key，就在启动期失败，而不是等到第一个用户按"否认"时才炸。
     *
     * <p>这与支付通道的处理同一取向：<b>配置缺失是装配问题，不该由业务运行时代偿</b>。
     */
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
            // 没有两张图就比不上：直接"无法判断"，一次调用都不发
            return Review.unknown("缺少关门留底照或空格基准照，无法比对（基准照由业务运维清柜时采集）");
        }
        try {
            return withThreshold(call(request));
        } catch (Exception e) {
            // 任何异常都是 UNKNOWN：模型不可用不能变成"自动放行"，也不能变成"自动定罪"
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
                // 思考 token 算在这里面：给小了正文会是空的（实测踩过）
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

    /**
     * 把模型正文翻成三值结论。<b>抽成静态方法是为了能被单测穷举</b>：模型返回的形状比想象的野得多
     * （带 markdown 代码块、前后有闲话、字段大小写不一、confidence 给字符串、正文为空）。
     * 这些情况全部要落到 UNKNOWN，一条都不许靠"看起来正常"放行。
     */
    static Review parse(String content) {
        if (content == null || content.isBlank()) {
            // 实测：思考 token 吃满预算时正文就是空的
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
            // 解析阶段不管阈值（阈值归 withThreshold 一处定），否则“什么算不可信”就有两套答案
            return new Review(presence, confidence, reason.isEmpty() ? null : reason);
        } catch (Exception malformed) {
            return Review.unknown("模型返回无法解析：" + trim(content, 60));
        }
    }

    /** 置信度不够一律降为 UNKNOWN（阈值可配）。单独一处：不能又要在解析里挡、又在这里挡。 */
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

    /**
     * 照片引用换成 data URI：现阶段（第 14/16 刀之前）引用就是本地或对象存储的路径。
     * 换成签名 URL 直传是第 16 刀接对象存储时的改动点，只在这一个方法里。
     */
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
