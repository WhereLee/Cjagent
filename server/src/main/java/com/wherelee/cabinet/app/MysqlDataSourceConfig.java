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

@Configuration
@EnableTransactionManagement
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
