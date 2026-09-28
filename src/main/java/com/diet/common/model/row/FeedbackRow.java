package com.diet.common.model.row;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FeedbackRow {
    private Long id;
    private Long userId;
    private String sessionId;
    private Long itemId;
    private String action;
    private Integer rating;
    private String reason;
    private LocalDateTime createdAt;
}