package com.diet.common.model.row;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RequestTraceRow {
    private Long id;
    private String traceId;
    private String sessionId;
    private Long userId;
    private String status;
    private Integer eventCount;
    private Long durationMs;
    private String errorMessage;
    private String traceJson;
    // ---- 第四周评估冗余列：flush 时从事件流聚合，评估可直读，避免逐行解析大 JSON ----
    /** 最终意图（INTENT_REVISED 事件的 intent 字段）。 */
    private String intentFinal;
    /** 澄清动作（CLARIFY_DECISION 事件的 action 字段）。 */
    private String clarifyAction;
    /** 整轮 token 总量（AGENT_CALL 事件 totalTokens 累加）。 */
    private Long tokenTotal;
    /** 是否触发 fallback/失败（1=是，0=否）。 */
    private Boolean fallbackUsed;
    private String expectedIntent;
    private String expectedSlots;
    private String expectedClarifyAction;
    private Long labeledBy;
    private LocalDateTime labeledAt;
    private String labelNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}