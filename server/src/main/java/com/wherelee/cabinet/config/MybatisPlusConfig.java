package com.wherelee.cabinet.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.wherelee.cabinet.infrastructure.mybatis.CabinetTenantHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置（只作用于业务主库 MySQL）。
 *
 * <p><b>插件顺序有语义，不能随意改</b>：租户拦截器必须在分页拦截器<b>之前</b>。
 * 顺序颠倒时分页插件会先把 SQL 包成 {@code select count(*)} 再去拼租户条件，
 * 结果是 total 算的是<b>全平台</b>的行数——数据看不串但分页数字错，很难归因。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(CabinetTenantHandler tenantHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1) 租户条件注入（必须第一个）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantHandler));

        // 2) 分页：限制单页最大条数，禁止 overflow 回跳首页，避免前端传个大页码把全表捞出来
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // 3) 阻断没有 where 条件的 update / delete，防手滑清库
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }
}
