package com.wherelee.cabinet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiExposureGuardTest {

    private static final Path API_ROOT = Path.of("src/main/java/com/wherelee/cabinet");
    private static final Path BASELINE = Path.of("src/test/resources/pii-exposure-baseline.txt");

    private static final String MASKED = "MASKED";
    private static final String PLAIN = "PLAIN";

    @Test
    @DisplayName("PII 出参清单里的每条都仍然存在，且脱敏状态没变")
    void baselineStillMatchesCode() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (String raw : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            checked++;

            String[] parts = line.split("\\s+");
            String[] typeAndField = parts[0].split("#");
            String type = typeAndField[0];
            String field = typeAndField[1];
            String expectedStatus = parts[1];

            Path source = findApiSource(type);
            if (source == null) {
                problems.add(type + "：在 api 包里找不到这个类（类改名或删了？清单要同步）");
                continue;
            }

            Boolean masked = scanMasked(source, field);
            if (masked == null) {
                problems.add(type + "#" + field + "：字段不存在了（改名或删了？清单要同步）");
                continue;
            }
            boolean expectMask = MASKED.equals(expectedStatus);
            if (masked != expectMask) {
                problems.add(type + "#" + field + "：清单写的是 " + expectedStatus
                        + "，代码里" + (masked ? "有" : "没有") + " @JsonMask");
            }
            if (!MASKED.equals(expectedStatus) && !PLAIN.equals(expectedStatus)) {
                problems.add(type + "#" + field + "：处置列只允许 " + MASKED + " / " + PLAIN);
            }
        }

        assertTrue(checked > 0, "一条清单项都没读到：" + BASELINE + " 是空文件？");
        assertTrue(problems.isEmpty(),
                "PII 清单与代码不一致（改字段或改脱敏时必须同步这张清单）：\n  " + String.join("\n  ", problems));
    }

    private Path findApiSource(String type) throws IOException {
        String suffix = "/api/" + type + ".java";
        try (Stream<Path> files = Files.walk(API_ROOT)) {
            return files.filter(path -> path.toString().replace('\\', '/').endsWith(suffix))
                    .findFirst()
                    .orElse(null);
        }
    }

    private Boolean scanMasked(Path source, String field) throws IOException {
        Pattern declaration = Pattern.compile("\\b" + Pattern.quote(field) + "\\b\\s*[,)]");
        boolean found = false;
        boolean masked = false;
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            if (declaration.matcher(line).find()) {
                found = true;
                if (line.contains("@JsonMask")) {
                    masked = true;
                }
            }
        }
        return found ? masked : null;
    }
}
