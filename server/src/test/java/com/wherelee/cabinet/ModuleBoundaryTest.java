package com.wherelee.cabinet;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块边界基线：<b>只允许收紧，不允许新增</b>。
 *
 * <p>现在的项目是"按层分包"（application/domain/infrastructure），业务边界靠自觉：
 * 订单服务可以随手 import 柜机、设备、资金、工单的 Mapper。重构的第一步不是搬文件，
 * 而是把现状冻成一份清单——之后每搬完一个服务，就删掉一批条目；清单归零那天，
 * 8 个业务服务的边界才真的成立。
 *
 * <p>为什么用 ArchUnit 而不是 grep import：依赖不只是 import，还有字段类型、方法参数、
 * 继承和注解。grep 会漏掉"我把 Mapper 当字段类型"这种更隐蔽的耦合。
 *
 * <p>基线数字来自重构前的实测（见 {@link #BASELINE}）。新增一条就红，删除不算红——
 * 但请顺手把对应条目从 BASELINE 删掉，别让清单变成"永远不会变的遗物"。
 */
class ModuleBoundaryTest {

    private static final String ROOT = "com.wherelee.cabinet";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ROOT);

    /**
     * 重构起点：当前存在的跨模块依赖对（忽略条数，只看“有没有”）。
     *
     * <p>条目形如 {@code app.storage->domain}。P1 起只减不增：每搜完一个服务，
     * 就删掉对应的一批行；这个块清空那天，8 个业务服务的边界才算真的成立。
     */
    private static final Set<String> BASELINE = loadBaseline();

    private static Set<String> loadBaseline() {
        try (java.io.InputStream in = ModuleBoundaryTest.class
                .getResourceAsStream("/module-boundary-baseline.txt")) {
            if (in == null) {
                throw new IllegalStateException("缺少模块边界基线文件 module-boundary-baseline.txt");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines().map(String::trim).filter(line -> !line.isEmpty())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("模块边界只能收紧：不允许新增跨模块依赖")
    void noNewCrossModuleDependency() {
        Set<String> actual = currentEdges();
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(BASELINE);
        assertTrue(added.isEmpty(),
                "新增了跨模块依赖（重构期只允许减少，请改走 api 端口）：" + added
                        + "\n当前全部依赖对：" + actual);
    }

    private static Set<String> currentEdges() {
        Set<String> edges = new TreeSet<>();
        for (JavaClass clazz : PRODUCTION_CLASSES) {
            String from = moduleOf(clazz.getPackageName());
            if (from == null) {
                continue;
            }
            for (Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
                String to = moduleOf(dependency.getTargetClass().getPackageName());
                if (to != null && !to.equals(from)) {
                    edges.add(from + "->" + to);
                }
            }
        }
        return edges;
    }

    /**
     * 包名 → 模块名。重构前只有"层"的概念，所以先按层聚合；P1 起这里会改成
     * {@code identity/locker/storage/...} 的服务名。
     */
    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(ROOT)) {
            return null;
        }
        String rest = packageName.substring(ROOT.length()).replaceFirst("^\\.", "");
        if (rest.isEmpty()) {
            return "root";
        }
        String[] parts = rest.split("\\.");
        return switch (parts[0]) {
            // 根包 app 是启动/装配层（原 config），与 application 的缩写 app 撞名，单独叫 boot
            case "app" -> "boot";
            case "application" -> parts.length > 1 ? "app." + parts[1] : "app";
            case "interfaces" -> parts.length > 1 ? "ui." + parts[1] : "ui";
            case "infrastructure" -> parts.length > 1 ? "infra." + parts[1] : "infra";
            default -> parts[0];
        };
    }
}
