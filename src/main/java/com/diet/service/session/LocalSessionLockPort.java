package com.diet.service.session;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 会话锁的本地实现（默认）：包装 {@link SessionLockManager} 的 JVM 内 ReentrantLock。
 * <p>
 * 单实例部署下零网络开销；unlock 通过 lockFor 重新取得同一锁实例
 * （Caffeine 缓存存活期内对同一 sessionId 稳定，极端逐出场景由 version CAS 兜底）。
 * 多实例部署时将 diet.lock.mode 改为 redis 切换到 Redisson 实现。
 */
@Component
@ConditionalOnProperty(name = "diet.lock.mode", havingValue = "local", matchIfMissing = true)
public class LocalSessionLockPort implements SessionLockPort {

    /** Caffeine 管理的会话锁缓存（第一周引入，防内存泄漏）。 */
    private final SessionLockManager sessionLockManager;

    public LocalSessionLockPort(SessionLockManager sessionLockManager) {
        this.sessionLockManager = sessionLockManager;
    }

    /** JVM 内 tryLock，语义与 ReentrantLock#tryLock(long, TimeUnit) 一致。 */
    @Override
    public boolean tryLock(String sessionId, long waitMs) throws InterruptedException {
        return sessionLockManager.lockFor(sessionId).tryLock(waitMs, TimeUnit.MILLISECONDS);
    }

    /** JVM 内 unlock：重新取得同一实例释放（持有校验由 ReentrantLock 自身完成）。 */
    @Override
    public void unlock(String sessionId) {
        sessionLockManager.lockFor(sessionId).unlock();
    }
}
