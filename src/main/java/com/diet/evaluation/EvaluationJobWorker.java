package com.diet.evaluation;

import com.diet.mapper.EvalJobMapper;
import com.diet.model.evaluation.EvaluationReport;
import com.diet.model.row.EvalJobRow;
import com.diet.model.web.EvaluationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 评估任务 worker（第四周）：@Scheduled 轮询任务表，认领 PENDING 任务后台执行。
 * <p>
 * 为什么不用 MQ：任务是低频、可容忍分钟级延迟、需要进度查询的批处理——任务表最透明，
 * 无外部组件；多实例部署时 selectNextPending 换成 SELECT ... FOR UPDATE SKIP LOCKED 即可
 * 多 worker 并行，模型不用变。为什么不用 @Async：进程内执行无法跨重启存活、无法汇报进度。
 */
@Component
public class EvaluationJobWorker {

    /** SLF4J 日志。 */
    private static final Logger log = LoggerFactory.getLogger(EvaluationJobWorker.class);

    /** 任务表 Mapper。 */
    private final EvalJobMapper evalJobMapper;

    /** 评估服务（复用同步评估的全部指标逻辑，只增加进度回调）。 */
    private final EvaluationService evaluationService;

    /** Jackson，报告序列化。 */
    private final ObjectMapper objectMapper;

    public EvaluationJobWorker(EvalJobMapper evalJobMapper, EvaluationService evaluationService, ObjectMapper objectMapper) {
        this.evalJobMapper = evalJobMapper;
        this.evaluationService = evaluationService;
        this.objectMapper = objectMapper;
    }

    /** 轮询认领并执行一个评估任务（单实例同时最多跑一个，避免与 LLM 配额互相挤占）。 */
    @Scheduled(fixedDelayString = "${diet.eval.job-poll-ms:5000}")
    public void pollAndRun() {
        try {
            runOneJob();
        } catch (Exception error) {
            // 调度线程内的兜底：数据库不可用等环境异常只记 debug，避免调度器错误刷屏
            log.debug("Eval job poll skipped: {}", error.getMessage());
        }
    }

    /** 单次"取任务 → 认领 → 执行 → 落报告"的完整流程。 */
    private void runOneJob() throws Exception {
        // 取最老的 PENDING 任务；队列为空直接返回
        EvalJobRow job = evalJobMapper.selectNextPending();
        if (job == null) {
            return;
        }
        // CAS 认领：影响行数 0 说明已被其他 worker 抢走，本轮跳过
        if (evalJobMapper.claim(job.getId()) == 0) {
            return;
        }
        try {
            // 重建评估请求（参数在提交时已校验）
            EvaluationRequest request = new EvaluationRequest(
                    job.getStartAt(), job.getEndAt(), job.getIncludeLlmJudge(), job.getLimitCount());
            // 逐条评估并通过回调实时汇报进度（前端轮询 processed/total）
            EvaluationReport report = evaluationService.evaluate(job.getUserId(), request, (processed, total) ->
                    evalJobMapper.updateProgress(job.getId(), total, processed));
            // 完成：状态 DONE + 报告落 JSON 列
            evalJobMapper.finish(job.getId(), objectMapper.writeValueAsString(report));
            log.info("Eval job {} done: limit={}", job.getId(), job.getLimitCount());
        } catch (Exception error) {
            // 失败：状态 FAILED + 原因，任务不静默消失
            evalJobMapper.fail(job.getId(), error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            log.warn("Eval job {} failed", job.getId(), error);
        }
    }

    /** 兜底复位：进程被杀后遗留的 RUNNING 任务（超过 30 分钟未更新）重置为 PENDING 断点续跑。 */
    @Scheduled(fixedDelayString = "${diet.eval.stale-reset-ms:600000}")
    public void resetStaleRunning() {
        try {
            int reset = evalJobMapper.resetStaleRunning(30);
            if (reset > 0) {
                log.warn("Reset {} stale RUNNING eval job(s) back to PENDING", reset);
            }
        } catch (Exception error) {
            log.debug("Eval stale reset skipped: {}", error.getMessage());
        }
    }
}
