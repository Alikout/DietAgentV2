package com.diet.model.row;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SessionRow {
    private String id;
    private Long userId;
    private String phase;
    private String slots;
    private String lastRecommendations;
    /** 乐观锁版本号，UPDATE 时作为 CAS 比对条件。 */
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}




