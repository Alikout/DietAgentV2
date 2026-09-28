package com.diet.session;

import com.diet.common.enums.SessionPhase;
import com.diet.common.enums.SourceMode;
import com.diet.exception.SessionConflictException;
import com.diet.mapper.SessionMapper;
import com.diet.common.model.domain.SessionState;
import com.diet.common.model.domain.SlotBundle;
import com.diet.session.lock.SessionCacheStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SessionStateService 单元测试：验证乐观锁 CAS 保存行为。
 * save 的 UPDATE 影响行数为 0 时必须抛 SessionConflictException（version 冲突）；
 * 保存成功时内存态 version 必须同步递增，与数据库保持一致。
 */
class SessionStateServiceTest {

    /** 被 mock 的会话 Mapper，控制 update 返回的影响行数。 */
    private SessionMapper sessionMapper;

    /** 被 mock 的 Redis 缓存（get 默认返回 null 走 MySQL 路径，put 为空操作）。 */
    private SessionCacheStore sessionCacheStore;

    /** 被测服务，使用真实 ObjectMapper 做 slots JSON 序列化。 */
    private SessionStateService sessionStateService;

    /** 每个用例前重置 mock 与被测服务。 */
    @BeforeEach
    void setUp() {
        sessionMapper = mock(SessionMapper.class);
        sessionCacheStore = mock(SessionCacheStore.class);
        sessionStateService = new SessionStateService(sessionMapper, new ObjectMapper(), sessionCacheStore);
    }

    /** 构造一个可合法序列化的会话状态（version=0，模拟刚从 DB 加载）。 */
    private SessionState freshState() {
        return SessionState.fresh("sess_test", 1L, SourceMode.PERSONAL);
    }

    /** UPDATE 影响行数为 0（version 已被并发修改）时抛 SessionConflictException。 */
    @Test
    void saveThrowsConflictWhenCasFails() {
        when(sessionMapper.update(any())).thenReturn(0);
        SessionState state = freshState().withPhase(SessionPhase.CLARIFY);

        assertThrows(SessionConflictException.class, () -> sessionStateService.save(state));
        verify(sessionMapper, times(1)).update(any());
    }

    /** UPDATE 成功时内存态 version 从 0 递增到 1，保持与数据库自增后一致。 */
    @Test
    void saveBumpsVersionOnSuccess() {
        when(sessionMapper.update(any())).thenReturn(1);
        SessionState state = freshState();
        assertEquals(0L, state.version());

        sessionStateService.save(state);

        assertEquals(1L, state.version());
    }

    /** version 为 null 的旧状态保存成功后应回退为 1，而不是 NPE。 */
    @Test
    void saveHandlesNullVersion() {
        when(sessionMapper.update(any())).thenReturn(1);
        SessionState state = freshState();
        state.version(null);

        sessionStateService.save(state);

        assertEquals(1L, state.version());
    }

    /** loadOrCreate 在请求 sessionId 为空时自动创建新会话（INSERT 一次）。 */
    @Test
    void loadOrCreateCreatesWhenSessionIdBlank() {
        SessionState state = sessionStateService.loadOrCreate(null, 1L, SourceMode.PERSONAL);

        assertEquals(SessionPhase.START, state.phase());
        assertEquals(0L, state.version());
        assertEquals(SlotBundle.empty(), state.slots());
        verify(sessionMapper, times(1)).insert(any());
    }

    /** 第二周锁瘦身：同 version 并发 save 必须恰好一个成功一个抛 SessionConflictException（CAS 暴露冲突）。 */
    @Test
    void concurrentSaveExposesConflictViaCas() throws Exception {
        // Mapper 桩：第一次 update 返回 1（成功），之后一律返回 0（version 已被占用）
        AtomicInteger calls = new AtomicInteger();
        when(sessionMapper.update(any())).thenAnswer(invocation -> calls.incrementAndGet() == 1 ? 1 : 0);
        // 两个线程共享同一个内存态状态（version 相同），模拟锁外并发轮次
        SessionState shared = freshState();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Runnable saveTask = () -> {
            try {
                sessionStateService.save(shared);
            } catch (Exception error) {
                failure.set(error);
            }
        };
        Thread first = new Thread(saveTask);
        Thread second = new Thread(saveTask);
        first.start();
        second.start();
        first.join();
        second.join();

        // 恰好一个线程保存失败，且失败类型是乐观锁冲突；成功者把内存态 version 递增到 1
        assertInstanceOf(SessionConflictException.class, failure.get());
        assertEquals(1L, shared.version());
        verify(sessionMapper, times(2)).update(any());
    }
}
