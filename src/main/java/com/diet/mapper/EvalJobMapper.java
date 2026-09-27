package com.diet.mapper;

import com.diet.model.row.EvalJobRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface EvalJobMapper {

    /** 插入 PENDING 任务，回填自增 id。 */
    int insert(EvalJobRow row);

    /** 按 id + userId 查询（归属校验）。 */
    EvalJobRow findById(@Param("id") Long id, @Param("userId") Long userId);

    /** 取最老的 PENDING 任务（worker 轮询用）。 */
    EvalJobRow selectNextPending();

    /** CAS 认领：仅当任务仍为 PENDING 时置为 RUNNING，影响行数 0 = 已被其他 worker 抢走。 */
    int claim(@Param("id") Long id);

    /** 汇报进度（worker 每批调用一次）。 */
    int updateProgress(@Param("id") Long id, @Param("total") int total, @Param("processed") int processed);

    /** 任务完成：状态置 DONE 并落报告 JSON。 */
    int finish(@Param("id") Long id, @Param("reportJson") String reportJson);

    /** 任务失败：状态置 FAILED 并记录原因。 */
    int fail(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    /** 把长时间未更新的 RUNNING 任务重置为 PENDING（进程被杀后的断点续跑）。 */
    int resetStaleRunning(@Param("staleMinutes") int staleMinutes);
}
