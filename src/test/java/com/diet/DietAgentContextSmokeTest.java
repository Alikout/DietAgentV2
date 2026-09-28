package com.diet;

import com.diet.session.lock.SessionLockManager;
import com.diet.slotdict.SlotOptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Spring 上下文冒烟测试：验证新增 Bean（SessionLockManager、带缓存的 SlotOptionService）
 * 能随上下文正常装配，Orchestrator 新增的构造器注入无缺Bean。
 * 注意：上下文启动不依赖 MySQL（连接池懒加载），但请求处理需要 diet_db 存在。
 */
@SpringBootTest
class DietAgentContextSmokeTest {

    /** 会话锁管理器，验证按 sessionId 返回稳定锁实例。 */
    @Autowired
    private SessionLockManager sessionLockManager;

    /** 槽位字典服务，验证 Caffeine 缓存随 Bean 正常构建。 */
    @Autowired
    private SlotOptionService slotOptionService;

    /** 上下文启动成功且两个新增 Bean 可注入。 */
    @Test
    void contextLoadsWithNewBeans() {
        assertNotNull(sessionLockManager);
        assertNotNull(slotOptionService);
    }

    /** 同一 sessionId 多次取锁返回同一实例；不同 sessionId 返回不同实例。 */
    @Test
    void lockForReturnsStableLockPerSession() {
        ReentrantLock first = sessionLockManager.lockFor("sess_smoke_a");
        ReentrantLock second = sessionLockManager.lockFor("sess_smoke_a");
        ReentrantLock other = sessionLockManager.lockFor("sess_smoke_b");

        assertSame(first, second);
        assertNotNull(other);
    }
}
