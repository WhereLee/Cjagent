package com.wherelee.cabinet;

import com.wherelee.cabinet.infrastructure.mq.RocketMQHealthIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中间件连通性集成测试。
 *
 * <p>本地无 Docker，跑不了 Testcontainers，所以这里直连本机真实实例：
 * 需要 MySQL(cabinet_dev) / PG(cabinet_agent) / Redis(db8) / RocketMQ 都已启动，
 * 且 server/.env 配好账密，否则本类会失败（这是有意的：环境没准备好就不该给我绿灯）。
 *
 * <p>执行：{@code mvn test -Pintegration}；默认 {@code mvn test} 会跳过（tag=integration）。
 */
@SpringBootTest
@Tag("integration")
class MiddlewareConnectivityTest {

    @Autowired
    @Qualifier("mysqlDataSource")
    private DataSource mysqlDataSource;

    @Autowired
    @Qualifier("pgDataSource")
    private DataSource pgDataSource;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RocketMQHealthIndicator rocketMQHealthIndicator;

    @Test
    @DisplayName("MySQL 业务主库可查，且连的是 cabinet_dev")
    void mysqlReachable() throws Exception {
        assertEquals(1, queryScalar(mysqlDataSource, "select 1"));
        assertEquals("cabinet_dev", queryString(mysqlDataSource, "select database()"));
    }

    @Test
    @DisplayName("PG agent 库可查且 pgvector 已启用")
    void pgReachableWithVector() throws Exception {
        assertEquals(1, queryScalar(pgDataSource, "select 1"));
        try (Connection c = pgDataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("select extversion from pg_extension where extname='vector'");
            assertTrue(rs.next(), "cabinet_agent 库未启用 pgvector 扩展");
            assertNotNull(rs.getString(1));
        }
    }

    @Test
    @DisplayName("Redis 用独立库位读写，且只碰 cab: 前缀的键")
    void redisReadWriteOnOwnDb() {
        String key = "cab:test:connectivity";
        stringRedisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(10));
        assertEquals("1", stringRedisTemplate.opsForValue().get(key));
        stringRedisTemplate.delete(key);
    }

    @Test
    @DisplayName("RocketMQ broker 已注册到 namesrv")
    void rocketMqBrokerRegistered() {
        var health = rocketMQHealthIndicator.health();
        assertEquals(Status.UP, health.getStatus(),
                "RocketMQ 不可用，明细: " + health.getDetails());
        assertTrue(((java.util.Set<?>) health.getDetails().get("brokers")).size() > 0,
                "namesrv 活着但没有任何 broker 注册");
    }

    private static int queryScalar(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(sql);
            assertTrue(rs.next(), "查询无结果: " + sql);
            return rs.getInt(1);
        }
    }

    private static String queryString(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(sql);
            assertTrue(rs.next(), "查询无结果: " + sql);
            return rs.getString(1);
        }
    }
}
