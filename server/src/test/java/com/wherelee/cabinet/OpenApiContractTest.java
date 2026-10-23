package com.wherelee.cabinet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 接口契约快照：<b>路径、参数、请求/响应模型一起比对</b>，不是只比路径。
 *
 * <p>为什么要有这一层：重构可以把类搬走、把服务切开，但对外契约不能变。路径集合的机检只能证明
 * “端点没少”，证明不了“字段没改”——DTO 挪包、记录瘦身、枚举换成字符串，都会在路径不变的前提下
 * 悄悄改掉前端契约。这里把 OpenAPI 文档整体冻成快照，任何一个字段增删都会当场红。
 *
 * <p><b>只剔除易变字段</b>：{@code operationId}（方法改名会变，计划里已声明可接受）、
 * {@code description}/{@code summary}（文案不是契约）、{@code servers}（随环境变）与
 * {@code x-*} 扩展字段。其余（paths、parameters、schemas、required、类型、枚举值）一律参与比对。
 *
 * <p>有意改契约时：设环境变量 {@code OPENAPI_CONTRACT_WRITE=1} 跑一次生成快照，
 * 人工复核 diff 后随同一次提交入库。不允许“为了让测试变绿”而无脑重生成。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 有意变更契约时置 1：只生成快照，不比对。 */
    private static final boolean WRITE = "1".equals(System.getenv("OPENAPI_CONTRACT_WRITE"))
            || Boolean.parseBoolean(System.getProperty("openapi.contract.write"));

    private static final Path SNAPSHOT = Path.of("src/test/resources/openapi-contract.json");

    /** 这些字段随实现细节变，不算契约。 */
    private static final Set<String> IGNORED = Set.of("operationId", "description", "summary", "servers");

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("接口契约不变：路径、参数与请求/响应模型与快照一致")
    void contractMatchesSnapshot() throws Exception {
        var result = mvc.perform(get("/v3/api-docs")).andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                "springdoc 未开启或文档路径变了：契约比对失去依据");
        JsonNode raw = JSON.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode actual = normalize(raw);

        if (WRITE) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n",
                    StandardCharsets.UTF_8);

            // 顺手把**未归一化**的原始文档落成前端类型的输入：`gen-api-types.mjs` 要用到 summary，
            // 而契约快照为了稳定比对把 summary/description 剔掉了，拿快照当输入会生成一堆空注释。
            // 这样“改契约 → 重生成快照 → 重生成前端类型”就不必再单独起一次服务拉 api-docs。
            // 按 group 拉：两个前端各自只该看到自己那一半路径（1-admin / 2-mini，见 OpenApiConfig）。
            // 写全量文档会让两份类型文件都撑成全集，前端那边就分不出哪边该调什么。
            for (String[] group : new String[][]{
                    {"1-admin", "api-docs-1-admin.json"},
                    {"2-mini", "api-docs-2-mini.json"}}) {
                var groupResult = mvc.perform(get("/v3/api-docs/" + group[0])).andReturn();
                if (groupResult.getResponse().getStatus() != 200) {
                    // group 改名不该让契约比对变红（那是另一件事），但会缺文件，前端脚本会报 MISSING
                    continue;
                }
                JsonNode groupDoc = JSON.readTree(
                        groupResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
                Path generated = Path.of("..", "scripts", "generated", group[1]);
                Files.createDirectories(generated.getParent());
                Files.writeString(generated,
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(groupDoc), StandardCharsets.UTF_8);
            }

            fail("已按 OPENAPI_CONTRACT_WRITE=1 重写契约快照与前端类型输入：请人工复核 diff 后再提交，不要把它当成测试通过");
        }
        if (!Files.exists(SNAPSHOT)) {
            fail("缺少契约快照 " + SNAPSHOT + "：设 OPENAPI_CONTRACT_WRITE=1 生成并复核后提交");
        }
        JsonNode expected = JSON.readTree(Files.readString(SNAPSHOT, StandardCharsets.UTF_8));
        assertEquals(expected, actual,
                "接口契约发生了变化：确认是有意的（改字段/加参数）再重生成快照，否则把改动改回去");
    }

    /**
     * 递归归一化：去掉易变字段与 {@code x-*} 扩展；对象键排序，避免序列化顺序造成假差异。
     * 数组保持原顺序（参数、枚举值、required 的顺序本身也是契约的一部分）。
     */
    private static JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (IGNORED.contains(field.getKey()) || field.getKey().startsWith("x-")) {
                    continue;
                }
                sorted.put(field.getKey(), normalize(field.getValue()));
            }
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            List<JsonNode> children = new ArrayList<>();
            node.forEach(child -> children.add(normalize(child)));
            children.forEach(out::add);
            return out;
        }
        return node;
    }
}
