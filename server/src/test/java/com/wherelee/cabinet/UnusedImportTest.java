package com.wherelee.cabinet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UnusedImportTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?!static)([\\w.]+)\\.(\\w+)\\s*;");

    @Test
    @DisplayName("src/main 里不存在未使用的 import")
    void noUnusedImports() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(file -> collect(file, offenders));
        }

        assertTrue(offenders.isEmpty(),
                "这些 import 在文件其余部分从未出现，删掉它们——它们会让读代码的人以为这个类依赖了什么：\n  "
                        + String.join("\n  ", offenders));
    }

    private void collect(Path file, List<String> offenders) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不了 " + file, e);
        }

        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            if (!line.startsWith("import ")) {
                body.append(line).append('\n');
            }
        }
        String text = body.toString();

        for (String line : lines) {
            Matcher matcher = IMPORT.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String simpleName = matcher.group(2);
            if ("*".equals(simpleName)) {
                continue;
            }
            if (!Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(text).find()) {
                offenders.add(file + "  ->  " + simpleName);
            }
        }
    }
}
