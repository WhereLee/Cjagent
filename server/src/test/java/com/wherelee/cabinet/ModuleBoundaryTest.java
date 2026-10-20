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
    private static final String BASELINE_TEXT = """
            app.auth->common
            app.auth->config
            app.auth->domain
            app.auth->infra.mapper
            app.auth->infra.security
            app.auth->infra.wechat
            app.billing->common
            app.billing->domain
            app.billing->infra.mapper
            app.device->app.task
            app.device->common
            app.device->domain
            app.device->infra.mapper
            app.locker->app.device
            app.locker->app.storage
            app.locker->app.support
            app.locker->common
            app.locker->domain
            app.locker->infra.mapper
            app.notify->common
            app.notify->domain
            app.notify->infra.mapper
            app.point->app.billing
            app.point->common
            app.point->domain
            app.point->infra.mapper
            app.reconcile->domain
            app.reconcile->infra.mapper
            app.storage->app.billing
            app.storage->app.device
            app.storage->app.notify
            app.storage->app.point
            app.storage->app.support
            app.storage->app.task
            app.storage->app.workorder
            app.storage->common
            app.storage->domain
            app.storage->infra.alloc
            app.storage->infra.mapper
            app.support->common
            app.support->domain
            app.support->infra.mapper
            app.task->app.billing
            app.task->app.device
            app.task->app.notify
            app.task->app.point
            app.task->app.reconcile
            app.task->app.storage
            app.task->app.workorder
            app.task->common
            app.task->domain
            app.task->infra.alloc
            app.task->infra.mapper
            app.user->app.support
            app.user->common
            app.user->domain
            app.user->infra.mapper
            app.workorder->app.notify
            app.workorder->app.support
            app.workorder->app.task
            app.workorder->common
            app.workorder->domain
            app.workorder->infra.mapper
            common->config
            common->domain
            config->app.task
            config->common
            config->domain
            config->infra.mapper
            config->infra.mybatis
            config->infra.security
            domain->common
            infra.ai->app.ops
            infra.ai->app.storage
            infra.ai->app.support
            infra.ai->app.workorder
            infra.ai->common
            infra.ai->domain
            infra.ai->infra.mapper
            infra.alloc->domain
            infra.audit->common
            infra.audit->domain
            infra.audit->infra.mapper
            infra.audit->infra.security
            infra.device->app.device
            infra.device->domain
            infra.idempotent->common
            infra.idempotent->infra.security
            infra.mapper->domain
            infra.mq->app.storage
            infra.mq->common
            infra.mybatis->common
            infra.mybatis->config
            infra.mybatis->domain
            infra.pay->app.point
            infra.ratelimit->common
            infra.ratelimit->infra.security
            infra.security->common
            infra.security->config
            infra.security->domain
            infra.security->infra.mapper
            infra.task->app.task
            infra.task->domain
            infra.wechat->common
            infra.wechat->config
            ui.admin->app.auth
            ui.admin->app.billing
            ui.admin->app.locker
            ui.admin->app.notify
            ui.admin->app.ops
            ui.admin->app.storage
            ui.admin->app.user
            ui.admin->app.workorder
            ui.admin->common
            ui.admin->domain
            ui.admin->infra.security
            ui.mini->app.auth
            ui.mini->app.notify
            ui.mini->app.point
            ui.mini->app.storage
            ui.mini->app.support
            ui.mini->app.workorder
            ui.mini->common
            ui.mini->domain
            ui.mini->infra.security
            ui.mq->app.storage
            ui.system->common
            """;
    
    private static final Set<String> BASELINE = BASELINE_TEXT.lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

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
            case "application" -> parts.length > 1 ? "app." + parts[1] : "app";
            case "interfaces" -> parts.length > 1 ? "ui." + parts[1] : "ui";
            case "infrastructure" -> parts.length > 1 ? "infra." + parts[1] : "infra";
            default -> parts[0];
        };
    }
}
