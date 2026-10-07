package com.wherelee.cabinet.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置（只作用于业务主库 MySQL）。
 *
 * <p>注意插件顺序：多租户 {@code TenantLineInnerInterceptor} 必须在分页插件<b>之前</b>添加，
 * 否则分页 count SQL 不会带上 tenant_id 条件，会出现越租户读到总数的严重问题。
 * 租户阶段（Security/RBAC 那一步）在此处 addInterceptor 到 index 0。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 分页：限制单页最大条数，禁止 overflow 回跳首页，避免前端传个大页码把全表捞出来
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // 阻断没有 where 条件的 update / delete，防手滑清库
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}
