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
    private static final Set<String> BASELINE = loadBaseline("/module-boundary-baseline.txt");

    /**
     * 跨服务“只能靠 api”的基线：与上面的模块基线同一套“只减不增”规则。
     *
     * <p>两个基线分开的原因：模块基线回答“这两个服务还认不认识”，层级基线回答
     * “认识得规不规范”。前者做到零不现实（storage 必然要用 locker 的端口），
     * 后者才是“服务能不能拆开”的硬条件。
     */
    private static final Set<String> LAYER_BASELINE = loadBaseline("/module-layer-baseline.txt");

    private static final Set<String> SERVICES =
            Set.of("identity", "locker", "storage", "funds", "device", "servicedesk", "notify", "assistant");

    private static final Set<String> LAYERS = Set.of("api", "app", "domain", "infra");

    private static Set<String> loadBaseline(String resource) {
        try (java.io.InputStream in = ModuleBoundaryTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少模块边界基线文件 " + resource);
            }
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            // Windows 下用 PowerShell 维护基线文件时会带 BOM，去掉后再按行读
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            return text.lines().map(String::trim).filter(line -> !line.isEmpty())
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
     * 跨服务只能靠 api：直接碰别人的 app/domain/infra 一律算越界。
     *
     * <p>为什么单独一条：模块基线只能回答“还认不认识”，而真正拔不开的是
     * “认识的方式”——订单直接读locker的 Mapper、工单直接拿订单实体。
     * 那些必须逐个换端口，这条规则让“换了几个”变成一个可数的数。
     */
    @Test
    @DisplayName("跨服务只能依赖对方的 api：不得直接碰 app/domain/infra")
    void crossServiceMustGoThroughApi() {
        Set<String> actual = layerViolations();
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(LAYER_BASELINE);
        assertTrue(added.isEmpty(),
                "新增了跨服务非 api 依赖（请改走端口）：" + added
                        + "\n当前全部越界：" + actual);
    }

    private static Set<String> layerViolations() {
        Set<String> out = new TreeSet<>();
        for (JavaClass clazz : PRODUCTION_CLASSES) {
            Module from = moduleAndLayer(clazz.getPackageName());
            if (from == null || !callerParticipates(from.name())) {
                continue;
            }
            for (Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
                Module to = moduleAndLayer(dependency.getTargetClass().getPackageName());
                if (to == null || !SERVICES.contains(to.name()) || to.name().equals(from.name())) {
                    continue;
                }
                // 对方的 api 是契约，允许；剩下的都是“拆不开”的地方
                if ("api".equals(to.layer())) {
                    continue;
                }
                out.add(from.name() + "->" + to.name() + ":" + to.layer());
            }
        }
        return out;
    }

    /** 服务、以及两个 BFF（它们也只能碰 api），都要受这一条约束。 */
    private static boolean callerParticipates(String module) {
        return SERVICES.contains(module) || module.startsWith("bff.");
    }

    /** 包名 → （模块名，层级）；不是服务包则返回 null。 */
    private static Module moduleAndLayer(String packageName) {
        if (!packageName.startsWith(ROOT)) {
            return null;
        }
        String rest = packageName.substring(ROOT.length()).replaceFirst("^\\.", "");
        String[] parts = rest.split("\\.");
        String head = parts[0];
        if ("interfaces".equals(head) && parts.length > 1) {
            return new Module("bff." + parts[1], "");
        }
        if ("bff".equals(head) && parts.length > 1) {
            return new Module("bff." + parts[1], "");
        }
        if (SERVICES.contains(head)) {
            String layer = parts.length > 1 && LAYERS.contains(parts[1]) ? parts[1] : "";
            return new Module(head, layer);
        }
        return null;
    }

    private record Module(String name, String layer) {
    }

    /**
     * 包名 → <b>目标服务名</b>。
     *
     * <p>不能按当前包名统计：P3 会把 {@code application.storage} 搬成 {@code storage}，
     * 键名一变，基线就看不出“到底拆没拆”。所以这里把新旧位置归一到同一个服务名，
     * 搬迁前后键不变，数字只会在“真的不再依赖”时下降。
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
        String head = parts[0];
        String sub = parts.length > 1 ? parts[1] : "";
        return switch (head) {
            // 根包 app 是启动/装配层（原 config），与 application 的缩写撞名，单独叫 boot
            case "app" -> "boot";
            case "application" -> serviceName(sub);
            case "interfaces" -> "bff." + sub;
            case "infrastructure" -> "infra." + sub;
            case "identity", "locker", "storage", "funds", "device", "servicedesk", "notify", "assistant" -> head;
            case "bff" -> "bff." + sub;
            case "platform" -> "platform";
            default -> head;
        };
    }
    
    /** 旧包名到目标服务名的映射：搬迁前后保持同一个键。 */
    private static String serviceName(String legacy) {
        return switch (legacy) {
            case "auth", "user" -> "identity";
            case "point", "billing", "reconcile" -> "funds";
            case "workorder" -> "servicedesk";
            case "support", "ops" -> "assistant";
            default -> legacy;
        };
    }
}
