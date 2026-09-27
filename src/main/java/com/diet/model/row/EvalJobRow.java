package com.diet.model.row;

import lombok.Data;

import java.time.LocalDateTime;

/** diet_eval_job 行对象（第四周评估异步化）。 */
@Data
public class EvalJobRow {
    private Long id;
    private Long userId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Boolean includeLlmJudge;
    private Integer limitCount;
    /** 任务状态：PENDING / RUNNING / DONE / FAILED。 */
    private String status;
    private Integer total;
    private Integer processed;
    private String reportJson;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
