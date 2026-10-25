package com.wherelee.cabinet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

class EndpointIdempotencyGuardTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path BASELINE = Path.of("src/test/resources/endpoint-idempotency-baseline.txt");

    private static final boolean WRITE = "1".equals(System.getenv("ENDPOINT_IDEMPOTENCY_WRITE"))
            || Boolean.parseBoolean(System.getProperty("endpoint.idempotency.write"));

    private static final Pattern WRITE_MAPPING = Pattern.compile("@(Post|Put|Delete)Mapping");

    private static final Pattern IDEMPOTENT = Pattern.compile("@Idempotent\\b");

    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?:public|private|protected)\\s+[\\w<>\\[\\], .]+?\\s+(\\w+)\\s*\\(");

    private static final String HEADER = """
            # 写端点 × 幂等处置清单。
            #
            # 每行：Controller#方法  处置
            #   IDEMPOTENT —— 标了 @Idempotent（弱网重试会被合并）
            #   UNGUARDED  —— 没标。它可能有唯一索引/状态条件更新兜底，也可能什么都没有；
            #                 本清单只记录"没有注解守卫"这个事实，不判断它是否安全
            #
            # UNGUARDED 是账，不是错误。补幂等属第三册 G-03 的后续，不在批 5。
            #
            # 有意更新：ENDPOINT_IDEMPOTENCY_WRITE=1 跑一次生成，人工复核后提交。
            """;

    @Test
    @DisplayName("写端点的幂等处置与清单一致")
    void writeEndpointsMatchBaseline() throws IOException {
        List<String> actual = scan();

        if (WRITE) {
            Files.writeString(BASELINE, render(actual), StandardCharsets.UTF_8);
            fail("已按 ENDPOINT_IDEMPOTENCY_WRITE=1 重写清单：请人工复核 diff 后再提交，不要把它当成测试通过");
        }

        assertFalse(actual.isEmpty(),
                "一个写端点都没扫到：扫描路径或正则失效了，这个守卫形同不存在");
        assertEquals(readBaseline(), actual,
                "写端点有变化。新增写端点时必须回答‘它靠什么防重’，再把答案写进 " + BASELINE);
    }

    private List<String> scan() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith("Controller.java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                String controller = file.getFileName().toString().replace(".java", "");
                for (int i = 0; i < lines.size(); i++) {
                    if (!WRITE_MAPPING.matcher(lines.get(i)).find()) {
                        continue;
                    }
                    String method = firstMethodAfter(lines, i + 1);
                    if (method == null) {
                        throw new IllegalStateException("在 " + file + ":" + (i + 1)
                                + " 找到写映射，却找不到下面的方法签名");
                    }
                    found.add(controller + "#" + method + "  " + (guardedAbove(lines, i) ? "IDEMPOTENT" : "UNGUARDED"));
                }
            }
        }
        Collections.sort(found);
        return found;
    }

    private boolean guardedAbove(List<String> lines, int mappingLine) {
        int from = Math.max(0, mappingLine - 15);
        for (int i = from; i <= mappingLine; i++) {
            if (IDEMPOTENT.matcher(lines.get(i)).find()) {
                return true;
            }
        }
        return false;
    }

    private String firstMethodAfter(List<String> lines, int from) {
        int end = Math.min(from + 8, lines.size());
        for (int i = from; i < end; i++) {
            Matcher matcher = METHOD.matcher(lines.get(i));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private String render(List<String> entries) {
        return HEADER + "\n" + String.join("\n", entries) + "\n";
    }

    private List<String> readBaseline() throws IOException {
        List<String> entries = new ArrayList<>();
        for (String line : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            entries.add(trimmed);
        }
        Collections.sort(entries);
        return entries;
    }
}
