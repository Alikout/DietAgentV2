package com.diet.exception;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理器，将业务异常统一转为 {"message": "..."} JSON 响应。
 * basePackages 必须与实际控制器包（com.diet.controller）一致，
 * 否则本 Advice 不会命中任何控制器，业务异常会退化为 Spring 默认错误页。
 */
@RestControllerAdvice(basePackages = "com.diet.controller")
public class DietExceptionHandler {

    /** Micrometer 指标中心，统计乐观锁冲突次数（diet.session.conflict），锁瘦身后的重试率可观测。 */
    private final MeterRegistry meterRegistry;

    public DietExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 业务异常 → 400，前端直接展示 message。
     */
    @ExceptionHandler(DietException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleNewDietException(DietException e) {
        return Map.of("message", e.getMessage());
    }

    /**
     * 会话状态乐观锁冲突 → 409，前端自动复用 requestId 重试一次。
     * 锁瘦身后同会话并发轮次依赖本机制自愈，冲突率经 diet.session.conflict 指标观测。
     */
    @ExceptionHandler(SessionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleSessionConflict(SessionConflictException e) {
        Counter.builder("diet.session.conflict").register(meterRegistry).increment();
        return Map.of("message", e.getMessage());
    }

    /**
     * 未预期异常 → 500，message 为空时返回兜底文案。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> handleException(Exception e) {
        return Map.of("message", e.getMessage() == null ? "服务异常" : e.getMessage());
    }
}