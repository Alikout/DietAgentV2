package com.diet.session.lock;

/**
 * 会话锁策略端口：按 sessionId 提供互斥锁的获取与释放。
 * <p>
 * 第二周锁瘦身（方案 B）后，锁只覆盖"读状态"短临界区，写冲突由 diet_sessions 的
 * version CAS 兜底。本接口把锁实现抽象出来：
 * <ul>
 *   <li>{@link LocalSessionLockPort}——单实例默认实现，JVM 内 ReentrantLock（零网络开销）；</li>
 *   <li>RedisSessionLockPort（第四周）——多实例部署时切换，Redisson 分布式锁，降低跨实例 409 率。</li>
 * </ul>
 * 通过 diet.lock.mode 配置切换实现。
 */
public interface SessionLockPort {

    /**
     * 尝试获取会话锁，最多等待 waitMs 毫秒。
     * 等待超时返回 false——调用方应快速失败（"上一条消息还在处理中"），不再无限排队。
     */
    boolean tryLock(String sessionId, long waitMs) throws InterruptedException;

    /** 释放会话锁。必须与成功返回 true 的 tryLock 成对调用（通常在 finally 中）。 */
    void unlock(String sessionId);
}
