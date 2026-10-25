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

class ModuleBoundaryTest {

    private static final String ROOT = "com.wherelee.cabinet";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ROOT);

    private static final Set<String> BASELINE = loadBaseline("/module-boundary-baseline.txt");

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

    private static boolean callerParticipates(String module) {
        return SERVICES.contains(module) || module.startsWith("bff.");
    }

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
            case "app" -> "boot";
            case "infrastructure" -> "infra." + sub;
            case "identity", "locker", "storage", "funds", "device", "servicedesk", "notify", "assistant" -> head;
            case "bff" -> "bff." + sub;
            case "platform" -> "platform";
            default -> head;
        };
    }
}
