package com.wherelee.cabinet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AllocationStrategyGuardTest {

    private static final Path ALLOCATOR_DIR = Path.of("src/main/java/com/wherelee/cabinet/locker/app");
    private static final Path TEST_DIR = Path.of("src/test/java/com/wherelee/cabinet/locker");

    private static final Pattern HAVING_VALUE = Pattern.compile(
            "@ConditionalOnProperty\\([^)]*havingValue\\s*=\\s*\"([a-z]+)\"");

    private static final Pattern TESTED_STRATEGY = Pattern.compile(
            "cabinet\\.alloc\\.strategy=([a-z]+)");

    @Test
    @DisplayName("每个可选分配策略都有行为契约测试")
    void everyStrategyHasBehaviourTest() throws IOException {
        Set<String> declared = scan(ALLOCATOR_DIR, "Allocator.java", HAVING_VALUE);
        Set<String> covered = scan(TEST_DIR, "AllocationTest.java", TESTED_STRATEGY);

        assertFalse(declared.isEmpty(),
                "一个策略实现都没扫到：扫描路径或正则失效了，这个守卫形同不存在");
        assertEquals(declared, covered,
                "策略实现与行为契约测试不一一对应。新增策略时必须补一个 AbstractAllocationBehaviourTest 子类，"
                        + "删策略时也要删对应测试。实现=" + declared + " 测试=" + covered);
    }

    private Set<String> scan(Path dir, String suffix, Pattern pattern) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(path -> path.toString().endsWith(suffix)).toList()) {
                Matcher matcher = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    found.add(matcher.group(1));
                }
            }
        }
        return found;
    }
}
