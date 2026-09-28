package com.diet.session.lock;

import com.diet.common.model.domain.SessionState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 会话状态 Redis 缓存（read-through / write-through，第四周）。
 * <p>
 * 写序保证正确性：只允许"MySQL CAS 成功之后"写缓存（SessionStateService#save），
 * 缓存永远不会领先于数据库；读路径 miss 时回源 MySQL 并回填。
 * 所有 Redis 操作带 try-catch 降级——Redis 故障时直落 MySQL，功能无损只是略慢。
 * TTL 2 小时 + 随机抖动（0~5 分钟），防同批过期雪崩。
 */
@Component
public class SessionCacheStore {

    /** SLF4J 日志，Redis 故障降级时打 debug。 */
    private static final Logger log = LoggerFactory.getLogger(SessionCacheStore.class);

    /** 缓存键前缀。 */
    private static final String KEY_PREFIX = "diet:session:";

    /** 基础 TTL：会话状态是热数据，2 小时未活跃即过期，由 MySQL 持久层兜底。 */
    private static final Duration BASE_TTL = Duration.ofHours(2);

    /** TTL 随机抖动上限（秒），打散过期时间点。 */
    private static final long TTL_JITTER_SECONDS = 300;

    /** Redis 模板（String 序列化，值由 Jackson 显式转换，避免默认 JDK 序列化的污染问题）。 */
    private final StringRedisTemplate redisTemplate;

    /** Jackson，SessionState 与 JSON 字符串互转。 */
    private final ObjectMapper objectMapper;

    public SessionCacheStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 读取缓存的会话状态；miss 或 Redis 故障返回 null（调用方回源 MySQL）。 */
    public SessionState get(String sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
            return json == null ? null : objectMapper.readValue(json, SessionState.class);
        } catch (Exception e) {
            // Redis 不可用/脏数据：降级为缓存未命中
            log.debug("Session cache read degraded: {}", e.getMessage());
            return null;
        }
    }

    /** 写入缓存的会话状态；Redis 故障静默降级（不影响主链路正确性）。 */
    public void put(String sessionId, SessionState state) {
        try {
            // TTL 加随机抖动，避免大量会话同一时刻过期击穿 MySQL
            Duration ttl = BASE_TTL.plusSeconds(ThreadLocalRandom.current().nextLong(TTL_JITTER_SECONDS));
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, objectMapper.writeValueAsString(state), ttl);
        } catch (Exception e) {
            log.debug("Session cache write degraded: {}", e.getMessage());
        }
    }
}
