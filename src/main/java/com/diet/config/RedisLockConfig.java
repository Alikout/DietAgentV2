package com.diet.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 分布式锁装配（第四周）：仅在 diet.lock.mode=redis 时创建客户端。
 * <p>
 * 刻意使用 redisson core 而不是 redisson-spring-boot-starter——starter 会无条件创建
 * RedissonClient 并在 Redis 不可用时阻断启动；按需装配后，默认 local 模式完全零 Redis 依赖。
 */
@Configuration
public class RedisLockConfig {

    /** 仅在 diet.lock.mode=redis 时装配：单机模式连接 spring.data.redis 指向的实例。 */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "diet.lock.mode", havingValue = "redis")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port
    ) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectTimeout(2000)
                .setTimeout(2000);
        return Redisson.create(config);
    }
}
