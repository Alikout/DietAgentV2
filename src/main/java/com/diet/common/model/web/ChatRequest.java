package com.diet.common.model.web;

import java.util.Map;

import com.diet.common.enums.SourceMode;
import com.fasterxml.jackson.annotation.JsonAutoDetect;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class ChatRequest {
    private String sessionId;
    private String message;
    private SourceMode sourceMode;
    private Map<String, Object> context;
    /** 客户端幂等键（UUID）：失败重试时复用同一值，服务端据此去重用户消息，防止重复消费 LLM。 */
    private String requestId;
}