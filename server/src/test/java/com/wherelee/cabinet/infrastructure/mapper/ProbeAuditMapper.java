package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.fixture.ProbeAudit;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 测试专用 Mapper。
 *
 * <p>放在主包名下是为了让 {@code MysqlDataSourceConfig} 的 @MapperScan 直接扫到，
 * 不需要为测试再加一套扫描配置；文件本身仍在 src/test 下，不进生产构件。
 */
public interface ProbeAuditMapper extends BaseMapper<ProbeAudit> {

    /**
     * 绕过逻辑删除直接看物理行。
     *
     * <p>必须有这个：{@code selectById} 在 @TableLogic 下会自动加 {@code deleted = 0}，
     * 用它断言"删除后查不到"永远为真，即使逻辑删除根本没生效也一样——测试就变成了自证。
     */
    @Select("select deleted from probe_audit where id = #{id}")
    Integer selectPhysicalDeleted(@Param("id") Long id);

    @Select("select count(*) from probe_audit where id = #{id}")
    int countPhysical(@Param("id") Long id);

    /** 校验 Flyway 迁移是否真的应用到当前库（版本按字符串传，MySQL 侧会做隐式转换）。 */
    @Select("select count(*) from flyway_schema_history where version = #{version} and success = 1")
    int countAppliedMigration(@Param("version") String version);

    /** 白名单表用的探针：租户拦截器不应给这两类表加条件。 */
    @Select("select count(*) from sys_tenant")
    int countTenantsWithoutContext();
}
