package com.diet.service.session;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 会话锁的 Redisson 实现（第四周，多实例部署时启用：diet.lock.mode=redis）。
 * <p>
 * 看门狗语义由显式 leaseTime 替代：租约 30s 覆盖"读状态"短临界区（毫秒级），
 * 即使持锁节点假死，锁也会在 30s 后自动释放，不会永久悬挂。
 * 分布式锁的意义是把跨实例的同会话并发写冲突（409）压到接近单实例水平；
 * 极端冲突仍由 diet_sessions 的 version CAS 兜底。
 */
@Component
@ConditionalOnProperty(name = "diet.lock.mode", havingValue = "redis")
public class RedisSessionLockPort implements SessionLockPort {

    /** 锁键前缀。 */
    private static final String KEY_PREFIX = "diet:session:lock:";

    /** 租约时长：远大于读临界区实际耗时，同时保证异常场景下锁可自愈。 */
    private static final long LEASE_MS = 30_000L;

    /** Redisson 客户端（RedisLockConfig 按条件装配）。 */
    private final RedissonClient redissonClient;

    public RedisSessionLockPort(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 分布式 tryLock：waitMs 内抢锁，抢到持锁 LEASE_MS。 */
    @Override
    public boolean tryLock(String sessionId, long waitMs) throws InterruptedException {
        RLock lock = redissonClient.getLock(KEY_PREFIX + sessionId);
        return lock.tryLock(waitMs, LEASE_MS, TimeUnit.MILLISECONDS);
    }

    /** 释放锁：校验持有者（防止租约过期后误释放他人的锁）。 */
    @Override
    public void unlock(String sessionId) {
        RLock lock = redissonClient.getLock(KEY_PREFIX + sessionId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
