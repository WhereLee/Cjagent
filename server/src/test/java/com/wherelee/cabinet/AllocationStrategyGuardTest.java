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

/**
 * 策略 × 行为契约的机器守卫。
 *
 * <p>为什么要有它：批 2 之前 {@code prealloc} 是四种策略里<b>唯一没有行为契约测试</b>的那一个，
 * 而它恰好也是唯一坏掉的那一个（存件 100% 返回 500）。"坏掉的那种策略恰好是唯一没测的那种"
 * 不是巧合——没有守卫，新增一种策略就会重复这件事。
 *
 * <p>两侧的真相都在代码里，不读注释也不读 yml 文案：
 * <ul>
 *   <li>有哪些策略：{@code SlotAllocator} 实现类上的 {@code @ConditionalOnProperty(havingValue = "x")}；</li>
 *   <li>哪个策略有测试：{@code *AllocationTest} 子类的 {@code @SpringBootTest(properties = "cabinet.alloc.strategy=x")}。</li>
 * </ul>
 * 两集合必须相等——少一个测试（新策略漏测）或者多一个测试（策略删了测试没删）都会红。
 */
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

    /** {@code suffix} 是普通结尾（如 {@code Allocator.java}），不是通配符——{@code endsWith} 不认星号。 */
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
