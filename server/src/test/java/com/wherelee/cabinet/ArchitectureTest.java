package com.wherelee.cabinet;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 分层约定的机器检查。
 *
 * <p>为什么值得为它引一个依赖：分层规则写在文档里靠人自律，一定会被破——通常是"赶进度时
 * Controller 直接注入 Mapper"，一次破例之后满屏照抄。这里让破坏约定的提交直接红。
 *
 * <p>刻意<b>不</b>检查 {@code config} 包：它是装配层，天然要同时引用 infrastructure 的东西
 * （切面、Provider）和配置属性，纳入环形依赖检查只会产生无意义告警。
 */
class ArchitectureTest {

    private static final String ROOT = "com.wherelee.cabinet";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ROOT);

    @Test
    @DisplayName("domain 保持纯净：不依赖任何外层")
    void domainIsPure() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".interfaces..", ROOT + ".application..",
                        ROOT + ".infrastructure..", ROOT + ".config..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("common 只做通用能力：不反向依赖业务层与技术实现层")
    void commonStaysGeneric() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".interfaces..", ROOT + ".application..", ROOT + ".infrastructure..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("infrastructure 不反向依赖接口层")
    void infrastructureDoesNotDependOnInterfaces() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".interfaces..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Controller 不得直接注入 Mapper（必须经 application 编排）")
    void controllersDoNotTouchMappers() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".interfaces..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure.mapper..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("后台与用户端互不依赖：两端凭证与语义不得混用")
    void endsAreIsolated() {
        ArchRule adminNotDependMini = noClasses().that().resideInAPackage(ROOT + ".interfaces.admin..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".interfaces.mini..");
        ArchRule miniNotDependAdmin = noClasses().that().resideInAPackage(ROOT + ".interfaces.mini..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".interfaces.admin..");
        adminNotDependMini.check(PRODUCTION_CLASSES);
        miniNotDependAdmin.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("技术细节不外泄：entity 的持久化注解只允许出现在 domain/infrastructure")
    void mybatisAnnotationsStayInPersistenceLayers() {
        // 防止 Mapper/PO 直接当接口出参：那样一改表结构就击穿到接口契约
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".interfaces..")
                .should().dependOnClassesThat().resideInAPackage("com.baomidou.mybatisplus.core.mapper..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("common 不依赖持久化框架（分页契约要能脱离 ORM 存立）")
    void commonHasNoPersistenceDependency() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".common..")
                .should().dependOnClassesThat().resideInAPackage("com.baomidou.mybatisplus..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("接口层不得直接使 MyBatis-Plus 分页对象（必须走 PageQuery/PageSupport）")
    void interfacesMustUsePageContract() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".interfaces..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.baomidou.mybatisplus.extension.plugins.pagination..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("实体不得出现在接口层（防实体直出）")
    void entitiesMustNotLeakToInterfaces() {
        // 起因：/api/mini/auth/me 直接返回了 SysRider，把 openId/unionId/deleted 下发给客户端。
        // 实体一旦当响应用，前端就会“顺手用起来”，以后改名或拆字段就是破契约
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".interfaces..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".domain.entity..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("application 不得反向依赖接口层（视图归 application）")
    void applicationDoesNotDependOnInterfaces() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".application..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".interfaces..");
        rule.check(PRODUCTION_CLASSES);
    }
}
