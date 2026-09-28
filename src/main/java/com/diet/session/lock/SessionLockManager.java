package com.diet.session.lock;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话级锁管理器：按 sessionId 提供可复用的 {@link ReentrantLock}，保证同一会话的请求在本实例内串行执行。
 * <p>
 * 为什么用 Caffeine 而不是 ConcurrentHashMap：旧实现（DietOrchestratorService 内的
 * {@code ConcurrentHashMap<String, Object> sessionLocks}）只增不减，每个历史 sessionId 永久占一条条目，
 * 长期运行内存缓慢泄漏。Caffeine 的 expireAfterAccess(10 分钟) + maximumSize(5 万) 让空闲会话的锁可被回收。
 * <p>
 * 为什么用 ReentrantLock 而不是 synchronized：为后续开启 Java 21 虚拟线程做准备——
 * synchronized 块会 pin 住载体线程（JDK 24 的 JEP 491 才修复），ReentrantLock 不会。
 * <p>
 * 边界说明：极端情况下锁条目在两个请求之间被逐出，短暂失去串行化保证；
 * 此时由 diet_sessions 的乐观锁（SessionStateService#save 的 version CAS）兜底发现冲突，
 * 内存锁 + DB CAS 构成分层防御。多实例部署时本类的锁只在单 JVM 内有效，
 * 跨实例串行化依赖乐观锁（或后续升级 Redis 分布式锁）。
 */
@Component
public class SessionLockManager {

    /** 锁缓存最大容量，超过后按 LRU 淘汰最久未访问的会话锁。 */
    private static final int MAX_LOCKS = 50_000;

    /** 锁空闲过期时间：持有中的锁刚被访问过不会被逐出，只有长期空闲的会话锁才会回收。 */
    private static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMinutes(10);

    /** sessionId → ReentrantLock 缓存，get 命中会刷新访问时间。 */
    private final Cache<String, ReentrantLock> locks = Caffeine.newBuilder()
            .maximumSize(MAX_LOCKS)
            .expireAfterAccess(EXPIRE_AFTER_ACCESS)
            .build();

    /**
     * 获取指定会话的锁，不存在时创建。
     * 返回的锁实例在缓存存活期内对同一 sessionId 稳定，调用方须在 finally 中 unlock。
     */
    public ReentrantLock lockFor(String sessionId) {
        return locks.get(sessionId, key -> new ReentrantLock());
    }
}
