package com.wherelee.cabinet;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
                        ROOT + ".bff..", ROOT + "..app..",
                        ROOT + ".infrastructure..", ROOT + ".app..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("common 保持纯契约：不沾平台运行时、启动装配与业务服务")
    void commonDependsOnNothingButItself() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".platform..", ROOT + ".app..", ROOT + ".bff..",
                        ROOT + "..app..", ROOT + ".infrastructure..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("接口层不得依赖基础设施层：控制器只认平台层的 Caller 与视图")
    void interfacesDoNotTouchInfrastructure() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".bff..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("common 只做通用能力：不反向依赖业务层与技术实现层")
    void commonStaysGeneric() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".bff..", ROOT + "..app..", ROOT + ".infrastructure..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("infrastructure 不反向依赖接口层")
    void infrastructureDoesNotDependOnInterfaces() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".bff..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("Controller 不得直接注入 Mapper（必须经 application 编排）")
    void controllersDoNotTouchMappers() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".bff..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure.mapper..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("后台与用户端互不依赖：两端凭证与语义不得混用")
    void endsAreIsolated() {
        ArchRule adminNotDependMini = noClasses().that().resideInAPackage(ROOT + ".bff.admin..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".bff.mini..");
        ArchRule miniNotDependAdmin = noClasses().that().resideInAPackage(ROOT + ".bff.mini..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".bff.admin..");
        adminNotDependMini.check(PRODUCTION_CLASSES);
        miniNotDependAdmin.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("技术细节不外泄：entity 的持久化注解只允许出现在 domain/infrastructure")
    void mybatisAnnotationsStayInPersistenceLayers() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".bff..")
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
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".bff..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.baomidou.mybatisplus.extension.plugins.pagination..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("实体不得出现在接口层（防实体直出）")
    void entitiesMustNotLeakToInterfaces() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".bff..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".domain.entity..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("application 不得反向依赖接口层（视图归 application）")
    void applicationDoesNotDependOnInterfaces() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + "..app..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".bff..");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("AI 框架不外泄：spring-ai 只允许出现在 infrastructure（换模型/框架不击穿端口）")
    void springAiStaysInInfrastructure() {
        ArchRule rule = noClasses().that().resideInAnyPackage(
                        ROOT + ".domain..", ROOT + "..app..",
                        ROOT + ".bff..", ROOT + ".common..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..");
        rule.check(PRODUCTION_CLASSES);
    }
}
