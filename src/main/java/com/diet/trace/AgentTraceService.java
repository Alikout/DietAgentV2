package com.diet.trace;

import com.diet.exception.DietException;
import com.diet.mapper.AgentTraceMapper;
import com.diet.model.row.RequestTraceRow;
import com.diet.model.web.TraceLabelRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Agent 链路追踪服务。
 * 通过 ThreadLocal {@link TraceScope} 收集一轮请求内的状态机事件和 Agent 调用，close 时异步写入 agent_traces 表。
 * <p>
 * 第二周引入的三项保护：
 * 1. LLM 调用超时——block(Duration) 按模型区分预算（light/main），杜绝线程无限挂起；
 * 2. Resilience4j 装饰——RateLimiter 保 DashScope 配额、Bulkhead 限在途并发、CircuitBreaker 连续故障短路，
 *    三类拒绝统一转为 {@link DietException}，由上层既有 catch 走模板兜底（宁降级，不排队）；
 * 3. Trace 异步落库——close 时快照事件列表提交到独立线程池，落库不再阻塞 HTTP 响应；
 *    队列满或落库失败只丢 Trace（有计数指标），绝不拖慢主链路。
 */
@Service
public class AgentTraceService {

    /** SLF4J 日志，Trace 落库失败时打 warn。 */
    private static final Logger log = LoggerFactory.getLogger(AgentTraceService.class);

    /** 按 sessionId 查询 Trace 时的默认条数上限。 */
    private static final int DEFAULT_LIMIT = 200;

    /** 按 sessionId 查询 Trace 时的最大条数上限，防止一次拉取过多。 */
    private static final int MAX_LIMIT = 1000;

    /** 单条 input/output payload 最大字符数，超出截断并追加 ...[truncated]。 */
    private static final int MAX_PAYLOAD_LENGTH = 20000;

    /** 当前请求线程的 Trace 上下文，openTrace 设置、TraceScope#close 清除。 */
    private final ThreadLocal<TraceScope> currentScope = new ThreadLocal<>();

    /** MyBatis Mapper，负责 agent_traces 表的 INSERT/SELECT。 */
    private final AgentTraceMapper agentTraceMapper;

    /** Jackson 序列化工具，将 payload 和 trace_json 转为 JSON 字符串。 */
    private final ObjectMapper objectMapper;

    /** Micrometer 指标中心，记录 LLM 超时/拒绝与 Trace 丢弃计数。 */
    private final MeterRegistry meterRegistry;

    /** RateLimiter 注册中心，实例 llm-main / llm-light 来自 application.yml。 */
    private final RateLimiterRegistry rateLimiterRegistry;

    /** Bulkhead 注册中心，实例 llm-main / llm-light。 */
    private final BulkheadRegistry bulkheadRegistry;

    /** CircuitBreaker 注册中心，实例 llm。 */
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /** 主模型名（推荐/规划链路），用于区分超时预算与限流实例。 */
    private final String mainModelName;

    /** 轻模型名（意图/澄清链路）。 */
    private final String lightModelName;

    /** 主模型调用超时预算。 */
    private final Duration mainCallTimeout;

    /** 轻模型调用超时预算。 */
    private final Duration lightCallTimeout;

    /** Trace 异步落库专用池：有界队列，满了丢弃（非关键路径，宁丢 Trace 不拖响应）。 */
    private final ThreadPoolTaskExecutor tracePersistExecutor;

    /**
     * 阶段事件（对外暴露的中性载荷）：eventType + phase。
     * SSE 端点监听本事件流，把状态机进度实时推给前端（第三周"让等待可见"）。
     */
    public record StageEvent(String eventType, String phase) {
    }

    /** 构造器注入 Mapper、Jackson、指标中心与 Resilience4j 注册中心。 */
    public AgentTraceService(
            AgentTraceMapper agentTraceMapper,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            RateLimiterRegistry rateLimiterRegistry,
            BulkheadRegistry bulkheadRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${diet.llm.main-model:qwen-max}") String mainModelName,
            @Value("${diet.llm.light-model:qwen-turbo}") String lightModelName,
            @Value("${diet.llm.main-call-timeout-seconds:15}") long mainCallTimeoutSeconds,
            @Value("${diet.llm.light-call-timeout-seconds:6}") long lightCallTimeoutSeconds
    ) {
        this.agentTraceMapper = agentTraceMapper;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.mainModelName = mainModelName;
        this.lightModelName = lightModelName;
        this.mainCallTimeout = Duration.ofSeconds(mainCallTimeoutSeconds);
        this.lightCallTimeout = Duration.ofSeconds(lightCallTimeoutSeconds);
        this.tracePersistExecutor = buildTraceExecutor();
    }

    /** 构建 Trace 落库线程池：1~2 个平台线程足够（纯 INSERT），队列 500，满了走 AbortPolicy 由 close 捕获计数。 */
    private ThreadPoolTaskExecutor buildTraceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("trace-persist-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }

    /**
     * 开启一轮 Trace 上下文。
     * 创建 TraceScope 并绑定到当前线程 ThreadLocal，供后续 recordEvent/callAgent 写入事件。
     */
    public TraceScope openTrace(String traceId, String sessionId, Long userId) {
        // 创建 TraceScope 实例，持有 traceId/sessionId/userId 和事件列表
        TraceScope scope = new TraceScope(traceId, sessionId, userId);
        // 将 scope 绑定到当前线程，record 方法通过 currentScope.get() 读取
        currentScope.set(scope);
        // 返回 scope 供 try-with-resources 在 finally 中 close
        return scope;
    }

    /**
     * 开启一轮 Trace 上下文并注册阶段事件监听器（第三周 SSE 用）。
     * listener 在每个事件追加后被同步回调；监听器异常不会影响 Trace 记录与主链路。
     */
    public TraceScope openTrace(String traceId, String sessionId, Long userId, Consumer<StageEvent> stageListener) {
        // 复用单参版本完成 ThreadLocal 绑定
        TraceScope scope = openTrace(traceId, sessionId, userId);
        // stageListener 为 null 时（普通同步调用）不注册监听
        if (stageListener != null) {
            scope.addStageListener(stageListener);
        }
        return scope;
    }

    /**
     * 记录状态机事件（无耗时）。
     * 委托 record 方法，agentName/modelName/latency/error 均为 null。
     */
    public void recordEvent(String eventType, String phase, Object inputPayload, Object outputPayload) {
        // eventType=事件名如 REQUEST_RECEIVED；phase=阶段如 HTTP/INTENT；input/output=入参和出参对象
        record(eventType, phase, null, null, inputPayload, outputPayload, null, null, null, null, null);
    }

    /**
     * 记录状态机事件并附带耗时（毫秒）。
     * 用于 REQUEST_FINISHED 等需要记录整段处理时间的场景。
     */
    public void recordEvent(String eventType, String phase, Object inputPayload, Object outputPayload, Long latencyMs) {
        // latencyMs 为从 startedAt 到当前的毫秒差
        record(eventType, phase, null, null, inputPayload, outputPayload, latencyMs, null, null, null, null);
    }

    /**
     * 记录异常事件。
     * 将 Trace 状态标记为 FAILED，并记录 errorMessage。
     */
    public void recordError(String eventType, String phase, Object inputPayload, Exception error) {
        // outputPayload 为 null，error 非 null 时会触发 scope.markFailed
        record(eventType, phase, null, null, inputPayload, null, null, null, null, null, error);
    }

    /** 统一 Agent 调用入口（旧签名）：按模型名自动解析超时预算。 */
    public Msg callAgent(String sessionId, String agentName, String modelName, ReActAgent agent, String inputText) {
        // 主模型用 main 预算，其余（light 模型）用 light 预算
        return callAgent(sessionId, agentName, modelName, agent, inputText, resolveTimeout(modelName));
    }

    /**
     * 统一 Agent 调用入口：Resilience4j 装饰（限流 → 隔离舱 → 熔断）+ 超时阻塞等待。
     * 成功/失败/被拒均记录 AGENT_CALL 事件（含 modelName、latency、error）。
     */
    public Msg callAgent(String sessionId, String agentName, String modelName, ReActAgent agent, String inputText, Duration timeout) {
        // 记录 Agent 调用开始时间（纳秒）
        long startedAt = System.nanoTime();
        // 按模型选择限流与隔离舱实例（主/轻模型分池，互不挤占）
        boolean isMain = isMainModel(modelName);
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(isMain ? "llm-main" : "llm-light");
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(isMain ? "llm-main" : "llm-light");
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("llm");
        try {
            // 构造 USER 角色消息，经装饰器组合（限流 → 隔离舱 → 熔断）调用 Agent（block 按预算等待 LLM 返回）
            Supplier<Msg> call = () -> agent.call(Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(inputText)
                    .build())
                    .block(timeout);
            Supplier<Msg> decorated = CircuitBreaker.decorateSupplier(circuitBreaker,
                    Bulkhead.decorateSupplier(bulkhead,
                            RateLimiter.decorateSupplier(rateLimiter, call)));
            Msg response = decorated.get();
            // 成功：记录 AGENT_CALL 事件，input=inputText，output=response 文本，latency=耗时 ms
            recordAgentCall(sessionId, agentName, modelName, inputText, response, elapsedMs(startedAt), null);
            // 将 Agent 原始响应返回给调用方（IntentAgent/ClarifyAgent/RecommendResponseAgent）
            return response;
        } catch (RequestNotPermitted | BulkheadFullException | CallNotPermittedException rejected) {
            // 限流/隔离舱满/熔断打开：计数并转为业务异常，上层既有 catch 走模板兜底
            counter("diet.llm.rejected", "model", modelName).increment();
            recordAgentCall(sessionId, agentName, modelName, inputText, null, elapsedMs(startedAt), rejected);
            // 丢弃原始拒绝异常细节，给前端统一可读文案
            throw new DietException("当前咨询人数较多，请稍后再试");
        } catch (RuntimeException error) {
            // block 超时抛 IllegalStateException（reactor "Timeout on blocking"），单独计数便于观察 DashScope 健康
            if (isBlockingTimeout(error)) {
                counter("diet.llm.timeout", "model", modelName).increment();
            }
            // 失败：记录 AGENT_CALL 事件，output=null，error 非空，并 markFailed
            recordAgentCall(sessionId, agentName, modelName, inputText, null, elapsedMs(startedAt), error);
            // 继续向上抛出，由调用方 catch 或 Orchestrator 捕获
            throw error;
        }
    }

    /** 判断 modelName 是否为主模型（推荐/规划链路）。 */
    private boolean isMainModel(String modelName) {
        return mainModelName.equals(modelName);
    }

    /** 按模型名解析超时预算：主模型 main-call-timeout，其余 light-call-timeout。 */
    private Duration resolveTimeout(String modelName) {
        return isMainModel(modelName) ? mainCallTimeout : lightCallTimeout;
    }

    /** reactor 阻塞超时特征：IllegalStateException 且消息含 "Timeout on blocking"。 */
    private boolean isBlockingTimeout(RuntimeException error) {
        return error instanceof IllegalStateException
                && error.getMessage() != null
                && error.getMessage().contains("Timeout on blocking");
    }

    /** 按 name+tags 取 Micrometer Counter（重复获取复用同一实例）。 */
    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    /** 按 traceId 查询单条链路追踪记录（供调试 API 使用）。 */
    public RequestTraceRow findByTraceId(Long userId, String traceId) {
        return agentTraceMapper.findByTraceId(userId, traceId);
    }

    /** 按 sessionId 查询最近 N 条链路追踪，limit 会被 clamp 到 [1, MAX_LIMIT]。 */
    public List<RequestTraceRow> findBySessionId(Long userId, String sessionId, Integer limit) {
        // null 时用 DEFAULT_LIMIT；否则限制在 1~MAX_LIMIT 之间
        int safeLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        return agentTraceMapper.findBySessionId(userId, sessionId, safeLimit);
    }

    /** 按时间范围查询 Trace，供后台标注页和评估接口复用。 */
    public List<RequestTraceRow> findByTimeRange(Long userId, LocalDateTime startAt, LocalDateTime endAt, Boolean onlyUnlabeled, Integer limit) {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new DietException("Trace 查询时间范围不合法");
        }
        int safeLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        return agentTraceMapper.findByTimeRange(userId, startAt, endAt, Boolean.TRUE.equals(onlyUnlabeled), safeLimit);
    }

    /** 保存人工标注的标准答案，直接写回 diet_request_trace。 */
    public void updateLabel(Long userId, String traceId, TraceLabelRequest request) {
        if (traceId == null || traceId.isBlank()) {
            throw new DietException("traceId 不能为空");
        }
        if (request == null) {
            throw new DietException("标注内容不能为空");
        }
        String expectedIntent = request.expectedIntent() == null ? null : request.expectedIntent().name();
        String expectedSlots = request.expectedSlots() == null ? null : toTraceJson(request.expectedSlots());
        String expectedClarifyAction = request.expectedClarifyAction() == null ? null : request.expectedClarifyAction().name();
        int updated = agentTraceMapper.updateLabel(
                userId,
                traceId,
                expectedIntent,
                expectedSlots,
                expectedClarifyAction,
                userId,
                request.labelNote()
        );
        if (updated == 0) {
            throw new DietException("Trace 不存在或无权限标注");
        }
    }

    /** 将 Agent 调用结果封装为 AGENT_CALL 类型事件写入 TraceScope。 */
    private void recordAgentCall(String sessionId, String agentName, String modelName, String inputText, Msg response, long latencyMs, Exception error) {
        // 从 Msg 中提取文本内容作为 output；response 为 null 时 output 也为 null
        Object output = response == null ? null : response.getTextContent();
        Long inputTokens = inputTokens(response);
        Long outputTokens = outputTokens(response);
        Long totalTokens = totalTokens(inputTokens, outputTokens);
        // 写入 eventType=AGENT_CALL，phase=AGENT，附带 agentName/modelName/token usage
        record("AGENT_CALL", "AGENT", agentName, modelName, inputText, output, latencyMs, inputTokens, outputTokens, totalTokens, error);
    }

    /**
     * 核心记录方法：将一条 TraceEvent 追加到当前 TraceScope。
     * 若 currentScope 为 null（未 openTrace）则静默跳过。
     */
    private void record(
            String eventType,
            String phase,
            String agentName,
            String modelName,
            Object inputPayload,
            Object outputPayload,
            Long latencyMs,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            Exception error) {
        // 从 ThreadLocal 获取当前请求的 TraceScope
        TraceScope scope = currentScope.get();
        // 未开启 Trace 时不记录（如单元测试或未包裹 openTrace 的调用）
        if (scope == null) {
            return;
        }
        // 将异常格式化为 "ClassName: message" 字符串，null 表示无异常
        String errorMessage = error == null ? null : trim(error.getClass().getSimpleName() + ": " + error.getMessage());
        // 构造 TraceEvent：stepOrder 自增，payload 序列化为 JSON 字符串
        scope.addEvent(new TraceEvent(
                scope.nextStep(),           // 事件序号，从 1 递增
                eventType,                  // 事件类型，如 REQUEST_RECEIVED / AGENT_CALL
                phase,                      // 阶段，如 HTTP / INTENT / AGENT
                agentName,                  // Agent 名，仅 AGENT_CALL 时有值
                modelName,                  // 模型名，仅 AGENT_CALL 时有值
                toPayload(inputPayload),    // 输入 payload JSON 字符串
                toPayload(outputPayload),   // 输出 payload JSON 字符串
                latencyMs,                  // 耗时毫秒，可为 null
                inputTokens,                // 输入 token 数，仅 AGENT_CALL 时有值
                outputTokens,               // 输出 token 数，仅 AGENT_CALL 时有值
                totalTokens,                // 输入 + 输出 token 数，仅 AGENT_CALL 时有值
                errorMessage,               // 错误信息，可为 null
                LocalDateTime.now().toString() // 事件创建时间 ISO 字符串
        ));
        // 通知阶段事件监听器（SSE 推送），监听器异常吞掉，不影响 Trace 与主链路
        scope.notifyStage(new StageEvent(eventType, phase));
        // 若有异常，将整轮 Trace 状态标记为 FAILED
        if (errorMessage != null) {
            scope.markFailed(errorMessage);
        }
    }

    /** 异步落库入口：将 scope + 事件快照写入 agent_traces 表（在 trace-persist 线程池内执行）。 */
    private void flushSnapshot(TraceScope scope, List<TraceEvent> snapshot) {
        // 构造数据库行对象 RequestTraceRow
        RequestTraceRow row = new RequestTraceRow();
        row.setTraceId(scope.traceId());           // 主键 traceId
        row.setSessionId(scope.sessionId());       // 关联 sessionId
        row.setUserId(scope.userId());             // 关联 userId
        row.setStatus(scope.status());             // SUCCESS 或 FAILED
        row.setEventCount(snapshot.size());        // 事件总数（以快照为准）
        row.setDurationMs(elapsedMs(scope.startedAt())); // 整轮耗时 ms
        row.setErrorMessage(scope.errorMessage()); // 失败时的错误摘要
        // 第四周评估冗余列：flush 时从事件流聚合（评估直读列，老数据列为空时回退解析 trace_json）
        fillEvaluationMetrics(row, snapshot);
        // 构造 trace_json 内容：traceId + sessionId + userId + status + durationMs + events 数组
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", scope.traceId());
        trace.put("sessionId", scope.sessionId());
        trace.put("userId", scope.userId());
        trace.put("status", scope.status());
        trace.put("durationMs", row.getDurationMs());
        trace.put("events", snapshot);             // 全部 TraceEvent 快照
        // 将 trace Map 序列化为 JSON 字符串写入 trace_json 列
        row.setTraceJson(toTraceJson(trace));
        // 执行 INSERT
        agentTraceMapper.insert(row);
    }

    /**
     * 从事件快照聚合评估所需的冗余指标：最终意图、澄清动作、token 总量、是否 fallback。
     * 事件 payload 已是 JSON 字符串，这里只做轻量字段抽取，落库线程内开销可忽略。
     */
    private void fillEvaluationMetrics(RequestTraceRow row, List<TraceEvent> snapshot) {
        // 最终意图，取 INTENT_REVISED（Orchestrator 修正后的意图）的 intent 字段
        String intentFinal = null;
        // 澄清动作，取 CLARIFY_DECISION 的 action 字段（ASK/READY）
        String clarifyAction = null;
        // token 累加器：只累加 AGENT_CALL 的 totalTokens，无任何 token 数据时保持 null
        Long tokenTotal = null;
        // 任一事件带错误或出现 REQUEST_FAILED 即视为触发 fallback
        boolean fallbackUsed = false;
        for (TraceEvent event : snapshot) {
            if (event.errorMessage() != null || "REQUEST_FAILED".equals(event.eventType())) {
                fallbackUsed = true;
            }
            if ("AGENT_CALL".equals(event.eventType()) && event.totalTokens() != null) {
                tokenTotal = (tokenTotal == null ? 0L : tokenTotal) + event.totalTokens();
            }
            if ("INTENT_REVISED".equals(event.eventType()) && intentFinal == null) {
                intentFinal = extractJsonField(event.outputPayload(), "intent");
            }
            if ("CLARIFY_DECISION".equals(event.eventType()) && clarifyAction == null) {
                clarifyAction = extractJsonField(event.outputPayload(), "action");
            }
        }
        row.setIntentFinal(intentFinal);
        row.setClarifyAction(clarifyAction);
        row.setTokenTotal(tokenTotal);
        row.setFallbackUsed(fallbackUsed);
    }

    /** 从事件 outputPayload（JSON 字符串）中提取指定标量字段；payload 为空/解析失败返回 null。 */
    private String extractJsonField(String payloadJson, String field) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            String value = objectMapper.readTree(payloadJson).path(field).asText(null);
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 将对象序列化为 JSON 字符串；失败时返回空 events 占位 JSON。 */
    private String toTraceJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{\"events\":[]}";
        }
    }

    /** 将 payload 对象转为可存储的字符串；String 直接 trim，其他对象 JSON 序列化。 */
    private String toPayload(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof String text) {
            return trim(text);
        }
        try {
            return trim(objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            return trim(String.valueOf(payload));
        }
    }

    /** 截断超长字符串，防止 trace_json 单条 payload 过大。 */
    private String trim(String text) {
        if (text == null || text.length() <= MAX_PAYLOAD_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_PAYLOAD_LENGTH) + "...[truncated]";
    }

    /** 从 Agent 响应中提取输入 token，usage 为空时返回 null。 */
    private Long inputTokens(Msg response) {
        if (response == null || response.getChatUsage() == null) {
            return null;
        }
        Number tokens = response.getChatUsage().getInputTokens();
        return tokens == null ? null : tokens.longValue();
    }

    /** 从 Agent 响应中提取输出 token，usage 为空时返回 null。 */
    private Long outputTokens(Msg response) {
        if (response == null || response.getChatUsage() == null) {
            return null;
        }
        Number tokens = response.getChatUsage().getOutputTokens();
        return tokens == null ? null : tokens.longValue();
    }

    /** 输入和输出 token 都取到时才计算总量，避免用不完整数据误导成本统计。 */
    private Long totalTokens(Long inputTokens, Long outputTokens) {
        if (inputTokens == null || outputTokens == null) {
            return null;
        }
        return inputTokens + outputTokens;
    }

    /** 纳秒时间戳转毫秒耗时。 */
    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /** 单条 Trace 事件记录，最终序列化进 trace_json.events 数组。 */
    private record TraceEvent(
            int stepOrder,       // 事件顺序号，从 1 开始
            String eventType,    // 事件类型名
            String phase,        // 所属阶段
            String agentName,    // Agent 名称（可空）
            String modelName,    // 模型名称（可空）
            String inputPayload, // 输入 JSON 字符串（可空）
            String outputPayload,// 输出 JSON 字符串（可空）
            Long latencyMs,      // 耗时毫秒（可空）
            Long inputTokens,    // 输入 token 数（可空）
            Long outputTokens,   // 输出 token 数（可空）
            Long totalTokens,    // 总 token 数（可空）
            String errorMessage, // 错误信息（可空）
            String createdAt     // 事件时间戳
    ) {
    }

    /**
     * 一轮请求的 Trace 生命周期容器。
     * 实现 AutoCloseable，配合 try-with-resources 在 close 时异步 flush 到 DB。
     */
    public final class TraceScope implements AutoCloseable {

        /** 本轮 traceId。 */
        private final String traceId;

        /** 本轮 sessionId。 */
        private final String sessionId;

        /** 本轮 userId。 */
        private final Long userId;

        /** 事件序号计数器，线程安全自增。 */
        private final AtomicInteger stepOrder = new AtomicInteger(0);

        /** Trace 开启时的纳秒时间戳，用于计算 durationMs。 */
        private final long startedAt = System.nanoTime();

        /** 本 scope 内累积的全部 TraceEvent。 */
        private final List<TraceEvent> events = new ArrayList<>();

        /** 阶段事件监听器（第三周 SSE）：CopyOnWriteArrayList 保证遍历安全，支持注册多个。 */
        private final List<Consumer<StageEvent>> stageListeners = new CopyOnWriteArrayList<>();

        /** Trace 整体状态，默认 SUCCESS，markFailed 后变 FAILED。 */
        private String status = "SUCCESS";

        /** 失败时的错误摘要。 */
        private String errorMessage;

        /** 是否已 close，防止重复 flush。 */
        private boolean closed;

        /** 私有构造，仅 AgentTraceService#openTrace 创建。 */
        private TraceScope(String traceId, String sessionId, Long userId) {
            this.traceId = traceId;
            this.sessionId = sessionId;
            this.userId = userId;
        }
        private String traceId() { return traceId; }
        private String sessionId() { return sessionId; }
        private Long userId() { return userId; }

        /** 返回下一个事件序号（先自增再返回）。 */
        private int nextStep() { return stepOrder.incrementAndGet(); }
        private long startedAt() { return startedAt; }

        /** 返回事件列表的不可变副本。 */
        private List<TraceEvent> events() { return List.copyOf(events); }
        private int eventCount() { return events.size(); }
        private String status() { return status; }
        private String errorMessage() { return errorMessage; }

        /** 追加一条事件到 events 列表。 */
        private void addEvent(TraceEvent event) { events.add(event); }

        /** 注册阶段事件监听器。 */
        private void addStageListener(Consumer<StageEvent> listener) { stageListeners.add(listener); }

        /** 逐个回调阶段监听器；单个监听器异常只吞掉自身，不影响其余监听与主链路。 */
        private void notifyStage(StageEvent stage) {
            for (Consumer<StageEvent> listener : stageListeners) {
                try {
                    listener.accept(stage);
                } catch (RuntimeException ignored) {
                    // SSE 推送失败（如客户端断开）不影响 Trace 记录与对话主链路
                }
            }
        }

        /** 将 Trace 标记为 FAILED 并记录错误信息。 */
        private void markFailed(String errorMessage) {
            this.status = "FAILED";
            this.errorMessage = errorMessage;
        }

        /**
         * close 时异步 flush 到 DB 并清除 ThreadLocal。
         * 关键点：scope 与事件快照以闭包捕获传给落库线程——异步线程不读 ThreadLocal
         * （拿不到 scope），内存可见性由 executor.execute 的 happens-before 语义保证。
         */
        @Override
        public void close() {
            // 已 close 则直接返回，避免重复 INSERT
            if (closed) {
                return;
            }
            closed = true;
            // 清除当前线程 Trace 上下文，防止线程池复用时污染
            currentScope.remove();
            // 提交前做不可变快照，落库线程只读快照，不再有并发写
            List<TraceEvent> snapshot = events();
            try {
                tracePersistExecutor.execute(() -> {
                    try {
                        flushSnapshot(this, snapshot);
                    } catch (RuntimeException error) {
                        // 落库失败只打 warn + 计数，不影响主业务返回
                        counter("diet.trace.persist.failure").increment();
                        log.warn("Failed to persist request trace: traceId={}", traceId, error);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                // 队列满：丢弃本轮 Trace（非关键路径），计数可观测
                counter("diet.trace.persist.discarded").increment();
                log.debug("Trace persist queue full, discarded: traceId={}", traceId);
            }
        }
    }
}
