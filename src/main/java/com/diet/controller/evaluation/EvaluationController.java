package com.diet.controller.evaluation;

import com.diet.common.model.web.EvaluationRequest;
import com.diet.security.CurrentUser;
import com.diet.evaluation.EvalJobService;
import com.diet.evaluation.EvaluationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 评估接口（第四周改造）：小批量同步执行，大批量走异步任务表。
 * <ul>
 *   <li>POST /evaluations——limit ≤ syncLimitMax 时同步返回报告（兼容旧行为）；否则创建任务返回 202 + jobId；</li>
 *   <li>POST /evaluations/jobs——显式提交异步任务，立即返回 jobId；</li>
 *   <li>GET /evaluations/jobs/{jobId}——轮询状态与进度，DONE 时携带完整报告。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/diet/evaluations")
public class EvaluationController {

    /** 同步评估的 limit 上限（超过转异步），来自配置 diet.eval.sync-limit-max。 */
    @Value("${diet.eval.sync-limit-max:50}")
    private int syncLimitMax;

    /** 评估服务（同步小批量）。 */
    private final EvaluationService evaluationService;

    /** 评估任务服务（异步大批量）。 */
    private final EvalJobService evalJobService;

    public EvaluationController(EvaluationService evaluationService, EvalJobService evalJobService) {
        this.evaluationService = evaluationService;
        this.evalJobService = evalJobService;
    }

    /**
     * POST /evaluations — 评估入口：按 limit 自动分派同步/异步。
     * 异步时返回 202 + {jobId, status}，前端轮询 GET /evaluations/jobs/{jobId}。
     */
    @PostMapping
    public ResponseEntity<Object> evaluate(@RequestBody EvaluationRequest request) {
        // 身份从 JWT 解析（CurrentUser），不再信任请求头
        Long userId = CurrentUser.id();
        // limit 缺省 1000；超过同步上限转异步任务
        int limit = request == null || request.limit() == null ? 1000 : request.limit();
        if (limit <= syncLimitMax) {
            return ResponseEntity.ok(evaluationService.evaluate(userId, request));
        }
        return ResponseEntity.accepted().body(Map.of(
                "jobId", evalJobService.submit(userId, request),
                "status", "PENDING"
        ));
    }

    /** POST /evaluations/jobs — 显式提交异步评估任务，立即返回 jobId。 */
    @PostMapping("/jobs")
    public Map<String, Object> submitJob(@RequestBody EvaluationRequest request) {
        Long userId = CurrentUser.id();
        return Map.of("jobId", evalJobService.submit(userId, request), "status", "PENDING");
    }

    /** GET /evaluations/jobs/{jobId} — 查询任务状态与进度；DONE 时携带完整评估报告。 */
    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> jobStatus(@PathVariable Long jobId) {
        Long userId = CurrentUser.id();
        return evalJobService.getJob(userId, jobId);
    }
}
