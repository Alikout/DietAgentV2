package com.diet.controller.chat;

import com.diet.model.web.ChatRequest;
import com.diet.model.web.ChatResponse;
import com.diet.security.CurrentUser;
import com.diet.conversation.orchestrator.DietOrchestratorService;
import com.diet.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 版对话入口（第三周"让等待可见"）。
 * <p>
 * 事件流协议（text/event-stream，客户端用 fetch + ReadableStream 消费，因为原生 EventSource
 * 不支持 POST 与自定义请求头）：
 * <ul>
 *   <li>{@code event: stage}——阶段进度，data 为 {"eventType":"INTENT_RECOGNIZED","phase":"INTENT"} 等中性事件；</li>
 *   <li>{@code event: done}——完整 ChatResponse JSON，与同步接口返回一致；</li>
 *   <li>{@code event: error}——{"message":"..."} 失败文案。</li>
 * </ul>
 * 实现要点：控制器立即返回 SseEmitter，对话在虚拟线程上执行（JDK 21，每个请求一个虚拟线程，
 * 阻塞近乎免费）；阶段事件由 AgentTraceService 的监听器机制转发，主链路零感知。
 */
@RestController
@RequestMapping("/api/v1/diet")
public class ChatStreamController {

    /** emitter 超时：大于 light(6s)+main(15s) 超时之和的预算，保证正常请求不被截断。 */
    private static final long EMITTER_TIMEOUT_MS = 65_000L;

    /** 阶段事件转发前的小延迟：等待容器完成 SseEmitter 异步初始化，避免首个事件早于 init 被拒。 */
    private static final long EMITTER_INIT_GRACE_MS = 100;

    /** 多 Agent 编排服务，注入后执行完整状态机。 */
    private final DietOrchestratorService orchestratorService;

    /** Jackson，序列化 SSE data 载荷。 */
    private final ObjectMapper objectMapper;

    /** SSE 请求执行器：JDK 21 虚拟线程池，每请求一个虚拟线程。 */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** Spring 构造器注入依赖。 */
    public ChatStreamController(DietOrchestratorService orchestratorService, ObjectMapper objectMapper) {
        this.orchestratorService = orchestratorService;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /api/v1/diet/chat/stream — SSE 版对话。
     * 请求体与 /chat 完全一致（sessionId、message、sourceMode、requestId）。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        // 身份取自 JWT subject（第四周鉴权）
        Long userId = CurrentUser.id();
        // 立即返回 emitter，把秒级对话挪到虚拟线程执行
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        // 客户端断开/超时的终止标记：置位后不再发送任何帧、不再重复 complete，
        // 避免对"已提交的响应"触发 Spring 错误页渲染（Cannot render error page 日志噪音）
        AtomicBoolean terminated = new AtomicBoolean(false);
        emitter.onCompletion(() -> terminated.set(true));
        emitter.onError(error -> terminated.set(true));
        emitter.onTimeout(() -> {
            terminated.set(true);
            completeQuietly(emitter);
        });
        streamExecutor.execute(() -> {
            // 等待容器初始化完成，避免首个 stage 事件早于 emitter 初始化抛 IllegalStateException
            try {
                Thread.sleep(EMITTER_INIT_GRACE_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            try {
                // 完整状态机：阶段事件经监听器转发为 SSE stage 帧
                ChatResponse response = orchestratorService.dietChat(userId, request, stage ->
                        sendQuietly(emitter, terminated, "stage", stage));
                // 完整结果一次性下发（与同步接口同构），前端据此渲染最终卡片
                sendQuietly(emitter, terminated, "done", response);
                completeQuietly(emitter);
            } catch (Exception error) {
                // 失败：error 帧携带可读文案，前端展示并允许重试
                sendQuietly(emitter, terminated, "error", Map.of(
                        "message", error.getMessage() == null ? "服务异常" : error.getMessage()));
                completeQuietly(emitter);
            }
        });
        return emitter;
    }

    /** 静默发送 SSE 帧：客户端断开等 IO 异常吞掉并置位终止标记，后续帧全部跳过。 */
    private void sendQuietly(SseEmitter emitter, AtomicBoolean terminated, String event, Object data) {
        // 已终止（断开/超时/完成）：直接跳过，不碰已提交的响应
        if (terminated.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
        } catch (Exception ignored) {
            // 客户端已断开或连接不可写：丢弃该帧并终止本轮流式输出
            terminated.set(true);
        }
    }

    /** 静默完成：emitter 可能已被超时/断开路径终止，complete 的重复调用异常吞掉。 */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已终止的 emitter 上 complete 是无害的空操作或抛轻量异常，均无需处理
        }
    }
}
