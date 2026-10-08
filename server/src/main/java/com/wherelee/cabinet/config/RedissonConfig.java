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
                .setConnectTimeout(3000);
        if (StringUtils.hasText(password)) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }
}
