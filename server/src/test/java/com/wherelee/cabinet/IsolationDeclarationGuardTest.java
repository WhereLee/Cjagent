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

class IsolationDeclarationGuardTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path BASELINE = Path.of("src/test/resources/isolation-baseline.txt");

    private static final Pattern ISOLATION =
            Pattern.compile("@Transactional\\([^)]*isolation\\s*=");

    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?:public|private|protected)\\s+[\\w<>\\[\\], .]+?\\s+(\\w+)\\s*\\(");

    @Test
    @DisplayName("@Transactional(isolation = ...) 的声明点与清单一致")
    void declarationsMatchBaseline() throws IOException {
        List<String> actual = scanDeclarations();
        List<String> expected = readBaseline();

        assertFalse(actual.isEmpty(),
                "一个声明点都没扫到：扫描路径或正则失效了，这个守卫形同不存在");
        assertEquals(expected, actual,
                "isolation 声明点有变化。新增时必须先回答它的‘无事务入口’在哪"
                        + "（否则那句 isolation 会被静默忽略），再把它写进 " + BASELINE);
    }

    private List<String> scanDeclarations() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                String className = file.getFileName().toString().replace(".java", "");
                for (int i = 0; i < lines.size(); i++) {
                    if (!ISOLATION.matcher(lines.get(i)).find()) {
                        continue;
                    }
                    String method = firstMethodAfter(lines, i + 1);
                    if (method == null) {
                        throw new IllegalStateException(
                                "在 " + file + ":" + (i + 1) + " 找到 isolation 声明，却找不到下面的方法签名");
                    }
                    found.add(className + "#" + method);
                }
            }
        }
        Collections.sort(found);
        return found;
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
