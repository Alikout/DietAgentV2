package com.diet.service.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 请求幂等服务（第四周）：requestId + Redis SETNX。
 * <p>
 * 拦截网络层重试、双击重复提交带来的重复 LLM 消费（一轮 qwen-max 是真实成本）。
 * Redis 不可用时跳过检查（返回 true），退化为 diet_messages.client_msg_id 唯一键去重——
 * 幂等是加速器不是依赖，故障不能阻断主链路。
 */
@Service
public class IdempotencyService {

    /** SLF4J 日志。 */
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** 幂等键前缀。 */
    private static final String KEY_PREFIX = "diet:idem:";

    /** Redis 模板。 */
    private final StringRedisTemplate redisTemplate;

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试占坑：首次返回 true 并占坑 TTL 时长；同键重复请求返回 false。
     * userId/requestId 缺失时不拦截（兼容旧客户端）。
     */
    public boolean tryAcquire(Long userId, String requestId, Duration ttl) {
        if (userId == null || requestId == null || requestId.isBlank()) {
            return true;
        }
        try {
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + userId + ":" + requestId, "processing", ttl);
            return !Boolean.FALSE.equals(first);
        } catch (Exception e) {
            // Redis 故障：跳过幂等检查，不阻断主链路
            log.debug("Idempotency check degraded (redis unavailable): {}", e.getMessage());
            return true;
        }
    }
}
