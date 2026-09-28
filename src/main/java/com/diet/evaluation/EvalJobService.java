package com.diet.evaluation;

import com.diet.exception.DietException;
import com.diet.mapper.EvalJobMapper;
import com.diet.model.row.EvalJobRow;
import com.diet.model.web.EvaluationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评估任务服务（第四周异步化）：任务表的提交与查询。
 * 提交即返回 jobId，执行由 {@link EvaluationJobWorker} 轮询认领，前端按 processed/total 展示进度。
 */
@Service
public class EvalJobService {

    /** 任务表 Mapper。 */
    private final EvalJobMapper evalJobMapper;

    /** Jackson，DONE 时把 report_json 解析为对象返回。 */
    private final ObjectMapper objectMapper;

    public EvalJobService(EvalJobMapper evalJobMapper, ObjectMapper objectMapper) {
        this.evalJobMapper = evalJobMapper;
        this.objectMapper = objectMapper;
    }

    /** 提交评估任务：校验窗口合法性后写入 PENDING，返回任务 id。 */
    public Long submit(Long userId, EvaluationRequest request) {
        // 窗口校验与同步接口保持同一标准
        if (request == null || request.startAt() == null || request.endAt() == null || !request.startAt().isBefore(request.endAt())) {
            throw new DietException("评估时间范围不合法");
        }
        EvalJobRow row = new EvalJobRow();
        row.setUserId(userId);
        row.setStartAt(request.startAt());
        row.setEndAt(request.endAt());
        row.setIncludeLlmJudge(Boolean.TRUE.equals(request.includeLlmJudge()));
        // limit 上限放宽到 1 万（异步任务不怕久），下限 1
        row.setLimitCount(request.limit() == null ? 1000 : Math.max(1, Math.min(request.limit(), 10_000)));
        row.setStatus("PENDING");
        row.setTotal(0);
        row.setProcessed(0);
        evalJobMapper.insert(row);
        return row.getId();
    }

    /** 查询任务状态与进度；DONE 时携带解析后的评估报告。 */
    public Map<String, Object> getJob(Long userId, Long jobId) {
        // 归属校验：只能看自己的任务
        EvalJobRow row = evalJobMapper.findById(jobId, userId);
        if (row == null) {
            throw new DietException("评估任务不存在或无权限查看");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", row.getId());
        result.put("status", row.getStatus());
        result.put("processed", row.getProcessed());
        result.put("total", row.getTotal());
        result.put("errorMessage", row.getErrorMessage());
        result.put("createdAt", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
        // DONE 时把报告 JSON 解析成对象嵌入，前端直接渲染
        if (row.getReportJson() != null) {
            try {
                result.put("report", objectMapper.readTree(row.getReportJson()));
            } catch (Exception ignored) {
                // 报告 JSON 损坏时不阻断状态查询
            }
        }
        return result;
    }
}
