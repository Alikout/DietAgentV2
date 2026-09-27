package com.diet.service.trace;

import com.diet.mapper.AgentTraceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Trace 保留策略定时任务（第四周数据治理）。
 * <p>
 * diet_request_trace 每轮对话 INSERT 一行（trace_json 单行几十 KB 量级），只增不删会让
 * 备份、查询与评估的解析成本线性恶化。本任务每日凌晨分批删除超过保留期的行，
 * DELETE LIMIT 循环避免长事务锁表；如需留档可在删除前先搬运到归档表（触发条件见方案文档）。
 */
@Component
public class TraceRetentionJob {

    /** SLF4J 日志。 */
    private static final Logger log = LoggerFactory.getLogger(TraceRetentionJob.class);

    /** Trace Mapper，执行分批 DELETE。 */
    private final AgentTraceMapper agentTraceMapper;

    /** 保留天数，来自配置 diet.trace.retention-days。 */
    private final int retentionDays;

    /** 单批删除行数，来自配置 diet.trace.purge-batch-size。 */
    private final int batchSize;

    public TraceRetentionJob(
            AgentTraceMapper agentTraceMapper,
            @Value("${diet.trace.retention-days:90}") int retentionDays,
            @Value("${diet.trace.purge-batch-size:1000}") int batchSize
    ) {
        this.agentTraceMapper = agentTraceMapper;
        this.retentionDays = retentionDays;
        this.batchSize = Math.max(1, batchSize);
    }

    /** 每日凌晨 03:30 执行：循环分批删除，直至该时间点之前的数据清理完毕。 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void purgeExpiredTraces() {
        try {
            doPurge();
        } catch (Exception error) {
            // 数据库不可用等环境异常：记日志后等待下一个周期，不让调度线程报错
            log.warn("Trace retention purge skipped: {}", error.getMessage());
        }
    }

    /** 实际清理流程。 */
    private void doPurge() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
        int total = 0;
        while (true) {
            int deleted = agentTraceMapper.deleteOlderThan(threshold, batchSize);
            total += deleted;
            // 本批删到的行数少于批量上限：说明已清理完毕
            if (deleted < batchSize) {
                break;
            }
        }
        // 清理量 > 0 时打 INFO 便于观察数据规模；平时静默
        if (total > 0) {
            log.info("Trace retention purge done: threshold={}, deleted={}", threshold, total);
        }
    }
}
