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

/**
 * 未使用 import 的机器守卫。
 *
 * <p>为什么要有它：项目里曾经一次扫出 224 处未用 import，集中在 8 个从大类拆出来的文件里
 * （拆的时候把 import 整块复制过去）。它们不影响编译，但会<b>误导读者</b>——
 * 一个 handler 里 import 了 `BizDeposit` 与 `FaultType`，读的人会以为它碰押金和故障分类。
 * 清了还会长回来，所以要有机器守。
 *
 * <p>为什么不用 checkstyle：项目已经为模块边界、接口契约各发明过一个"测试即守卫"的手法
 * （{@code ModuleBoundaryTest}、{@code OpenApiContractTest}），用同一套不需要新依赖，
 * 也不会出现"清理脚本一套标准、检查工具另一套标准"的分裂。
 *
 * <p><b>判定是保守的</b>：文件其余部分（含注释）出现过的类名就算"用到"。
 * 所以只会漏报，不会误报——{@code javadoc} 里 {@code {@link Xxx}} 提到的类不会被误删。
 */
class UnusedImportTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /** 只处理普通 import：{@code import static} 的末段是成员名，不能按类名判。 */
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
            // 按整词匹配：Foo 的 import 被 FooBar 的使用命中，是这里唯一要防的误判
            if (!Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(text).find()) {
                offenders.add(file + "  ->  " + simpleName);
            }
        }
    }
}
