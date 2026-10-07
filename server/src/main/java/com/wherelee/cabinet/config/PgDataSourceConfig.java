package com.wherelee.cabinet.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * agent / 向量库 PostgreSQL：不走 MyBatis，只用 JdbcTemplate。
 *
 * <p>原因：pgvector 的 {@code vector} 类型、数组、JSONB 在 ORM 里映射成本高且收益低，
 * 检索类 SQL 本来就要写具体的向量表达式，直接用 JdbcTemplate / 原生 SQL 更清楚。
 * 事务管理器单独命名，避免与主库 {@code transactionManager} 混淆。
 */
@Configuration
public class PgDataSourceConfig {

    @Bean(name = "pgDataSource")
    @ConfigurationProperties("cabinet.datasource.pg")
    public DataSource pgDataSource() {
        return new HikariDataSource();
    }

    @Bean(name = "pgJdbcTemplate")
    public JdbcTemplate pgJdbcTemplate(@Qualifier("pgDataSource") DataSource pgDataSource) {
        return new JdbcTemplate(pgDataSource);
    }

    @Bean(name = "pgTransactionManager")
    public PlatformTransactionManager pgTransactionManager(@Qualifier("pgDataSource") DataSource pgDataSource) {
        return new DataSourceTransactionManager(pgDataSource);
    }
}
