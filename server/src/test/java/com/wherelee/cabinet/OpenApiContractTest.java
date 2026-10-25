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

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final boolean WRITE = "1".equals(System.getenv("OPENAPI_CONTRACT_WRITE"))
            || Boolean.parseBoolean(System.getProperty("openapi.contract.write"));

    private static final Path SNAPSHOT = Path.of("src/test/resources/openapi-contract.json");

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

            for (String[] group : new String[][]{
                    {"1-admin", "api-docs-1-admin.json"},
                    {"2-mini", "api-docs-2-mini.json"}}) {
                var groupResult = mvc.perform(get("/v3/api-docs/" + group[0])).andReturn();
                if (groupResult.getResponse().getStatus() != 200) {
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
