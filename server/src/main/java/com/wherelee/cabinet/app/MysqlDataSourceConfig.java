package com.wherelee.cabinet.app;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.annotations.Mapper;
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
 * <p>显式标 @Primary：批 8 之后容器里只剩 MySQL 一个数据源，但它仍然留着——
 * 将来再加任何数据源时，MyBatis-Plus 与事务管理器不必重新学习“该用哪个”，
 * 也不会在启动时报 "multiple DataSource beans"。
 *
 * <p>事务管理器必须显式声明并标 @Primary：Boot 的
 * DataSourceTransactionManagerAutoConfiguration 是
 * {@code @ConditionalOnMissingBean(PlatformTransactionManager.class)}，
 * 一旦某个非 @Primary 的事务管理器先注册，自动配置就会退让，
 * 导致 {@code @Transactional} 默默作用在别的连接上——主库回滚失效且不报错。
 */
@Configuration
@EnableTransactionManagement
// 按 @Mapper 注解扫，而不是写死某个包：服务化后 mapper 会分散到各服务的 infra 包，
// 写死前缀会漏掉新搬的 mapper（显式包列表也会随服务数量增长而漂移）。
@MapperScan(basePackages = "com.wherelee.cabinet", annotationClass = Mapper.class)
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
