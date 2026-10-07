package com.wherelee.cabinet.config;

import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * 业务主库 MySQL：唯一被 MyBatis-Plus 接管的数据源。
 *
 * <p>标 @Primary 是因为容器里存在多个 DataSource Bean，MyBatis-Plus / 事务管理器
 * 必须明确知道用哪一个；不写 Primary 会在启动时报 “multiple DataSource beans”。
 *
 * <p>事务管理器必须显式声明并标 @Primary：Boot 的
 * DataSourceTransactionManagerAutoConfiguration 是
 * {@code @ConditionalOnMissingBean(PlatformTransactionManager.class)}，
 * 一旦 PgDataSourceConfig 先注册了 pgTransactionManager，自动配置就会退让，
 * 导致 {@code @Transactional} 默默作用在 PG 连接上——主库回滚失效且不报错。
 */
@Configuration
@EnableTransactionManagement
@MapperScan("com.wherelee.cabinet.infrastructure.mapper")
public class MysqlDataSourceConfig {

    @Bean(name = "mysqlDataSource")
    @Primary
    @ConfigurationProperties("cabinet.datasource.mysql")
    public DataSource mysqlDataSource() {
        return new HikariDataSource();
    }

    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager mysqlTransactionManager(@Qualifier("mysqlDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
