package com.wherelee.cabinet.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端（只用于分布式锁）。
 *
 * <p><b>连接参数复用 spring.data.redis.*</b>：本项目已有 StringRedisTemplate（限流、幂等、
 * 预扣计数都走它）。如果 Redisson 单独配一套 host/db，很容易出现“锁在 db3、数据在 db8”
 * 这种查起来要人命的错位——共用同一份配置源是唯一可靠的防错方式。
 *
 * <p>不用 spring-boot-starter-redisson：它的自动配置会接管连接工厂，而我们要的是显式、可预测的客户端。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.database:8}")
    private int database;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                // 命令超时给短值：锁竞争失败要快速返回，不能让请求线程挂在慢命令上
                .setTimeout(3000)
                .setConnectTimeout(3000)
                // 重试间隔固定：默认的 EqualJitterDelay(1s,2s) 会算出 500~1000ms 的**随机**值，
                // 而 Redisson 用 min(retryDelay, timeout) 推导 HashedWheelTimer 的 tick：
                // tick = (x % 100) / 2，只要随机值落在 501/601/701… 上就得用 0，
                // 启动直接抱 IllegalArgumentException: tickDuration : 0（实测约 1% 的启动会中，
                // 且完全不可复现）。不显式定住这个值，就是在把“应用能不能起来”交给随机数。
                .setRetryDelay(new org.redisson.config.ConstantDelay(java.time.Duration.ofMillis(100)));
        if (StringUtils.hasText(password)) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }
}
