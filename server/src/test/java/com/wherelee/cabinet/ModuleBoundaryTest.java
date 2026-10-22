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
 * <p>这份清单是重构起点把"谁还认识谁"冻下来的结果：当时项目按层分包
 * （application/domain/infrastructure），业务边界全靠自觉——订单服务可以随手 import
 * 柜机、设备、资金、工单的 Mapper。做法是先把现状冻成清单，之后每搬完一个服务就删掉一批条目。
 *
 * <p>搬包已经完成（8 个业务服务各自 api/app/domain/infra，加上 platform 与 bff），
 * 清单也按实测收紧过：指向已不存在的顶层 {@code task} 包的 5 条，
 * 以及 {@code servicedesk->assistant}、{@code storage->funds} 两条已消失的依赖都删掉了
 * （后者现在是反向的 {@code funds->storage}：storage 只在自己 api 包里暴露 FundPort，由 funds 实现）。
 * 剩下的条目说的是"还认识"，不是"该认识"——清单归零那天，8 个服务的边界才真的成立。
 *
 * <p>为什么用 ArchUnit 而不是 grep import：依赖不只是 import，还有字段类型、方法参数、
 * 继承和注解。grep 会漏掉"我把 Mapper 当字段类型"这种更隐蔽的耦合。
 *
 * <p>新增一条就红，删除不算红——但请顺手把对应条目从 BASELINE 删掉，
 * 别让清单变成"永远不会变的遗物"。取实际依赖全集的办法：把基线文件临时清空后跑
 * {@link #noNewCrossModuleDependency}，断言消息里会打印"当前全部依赖对"。
 */
class ModuleBoundaryTest {

    private static final String ROOT = "com.wherelee.cabinet";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ROOT);

    /**
     * 当前仍存在的跨模块依赖对（忽略条数，只看“有没有”）。
     *
     * <p>条目形如 {@code storage->domain}：箭头左边是源模块、右边是目标模块，
     * 模块名由 {@link #moduleOf} 从包名归一而来。只减不增——每换掉一处直接依赖，
     * 就删掉对应的一批行；这个文件清空那天，8 个业务服务的边界才算真的成立。
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
        return layerViolations(false);
    }

    /**
     * 硬规则：跨服务不得直接碰对方的 domain/infra。<b>没有基线、零容忍</b>。
     *
     * <p>这一条与上面两条的区别：上面允许“先把现状冻住再逐条换端口”，
     * 而实体与 Mapper 直连是“拆不开”的硬伤（改一张表就击穿两个服务），
     * 所以它必须是空的——空了之后任何一条新增都会当场红。
     */
    @Test
    @DisplayName("跨服务不得直接依赖对象的 domain/infra（零容忍）")
    void crossServiceMustNotTouchDomainOrInfra() {
        Set<String> actual = layerViolations(true);
        assertTrue(actual.isEmpty(),
                "出现了跨服务的 domain/infra 依赖（必须改走 api 端口）：" + actual);
    }

    private static Set<String> layerViolations(boolean onlyDomainOrInfra) {
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
                if (onlyDomainOrInfra && !("domain".equals(to.layer()) || "infra".equals(to.layer()))) {
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
     * <p>为什么要有这层映射而不是直接拿包名当键：包名会随重构搬家，键名一变，
     * 基线就看不出“到底拆没拆”——数字下降可能只是改了个包名。归一到服务名之后，
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
            // 根包 app 是启动/装配层（原 config），单独叫 boot：
            // 不区分就会与各服务自己的 app 层混成同一个名字
            case "app" -> "boot";
            case "infrastructure" -> "infra." + sub;
            case "identity", "locker", "storage", "funds", "device", "servicedesk", "notify", "assistant" -> head;
            case "bff" -> "bff." + sub;
            case "platform" -> "platform";
            default -> head;
        };
    }
}
