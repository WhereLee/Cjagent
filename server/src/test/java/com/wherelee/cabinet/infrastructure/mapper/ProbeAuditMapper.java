package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.fixture.ProbeAudit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProbeAuditMapper extends BaseMapper<ProbeAudit> {

    @Select("select deleted from probe_audit where id = #{id}")
    Integer selectPhysicalDeleted(@Param("id") Long id);

    @Select("select count(*) from probe_audit where id = #{id}")
    int countPhysical(@Param("id") Long id);

    @Select("select count(*) from flyway_schema_history where version = #{version} and success = 1")
    int countAppliedMigration(@Param("version") String version);

    @Select("select count(*) from sys_tenant")
    int countTenantsWithoutContext();
}
