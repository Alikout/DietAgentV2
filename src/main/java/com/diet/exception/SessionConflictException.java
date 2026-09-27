package com.diet.exception;

/**
 * 会话状态乐观锁冲突异常。
 * 当 SessionStateService#save 的 CAS UPDATE（WHERE version = #{version}）影响行数为 0 时抛出，
 * 说明同一会话的状态已被其他请求（多实例部署下的并发轮次）先一步修改。
 * 由 DietExceptionHandler 映射为 HTTP 409，前端提示用户重试即可；不做自动重试，
 * 因为重试意味着重跑整轮 LLM 调用，成本与延迟都不划算。
 */
public class SessionConflictException extends RuntimeException {

    /** 默认冲突提示文案。 */
    private static final String DEFAULT_MESSAGE = "会话状态已被其他请求修改，请重试";

    /** 使用默认提示文案构造冲突异常。 */
    public SessionConflictException() {
        super(DEFAULT_MESSAGE);
    }

    /** 使用自定义提示文案构造冲突异常。 */
    public SessionConflictException(String message) {
        super(message);
    }
}
