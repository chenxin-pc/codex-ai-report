package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportChunkTagJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * @Description: chunk 标签抽取 job Mapper，负责 job 创建、领取、成功和失败状态流转。
 * @Logic: 调度服务通过该 Mapper 保证标签抽取任务可重试、可观测且支持历史重打标。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: job 实体列表或受影响行数。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Mapper
public interface ReportChunkTagJobMapper {

    /**
     * @Description: 插入标签抽取 job。
     * @Logic: chunk 入库或历史重打标时创建 PENDING 任务。
     * @Param: job 标签抽取 job 实体。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    int insert(ReportChunkTagJob job);

    /**
     * @Description: 按 chunk 和词库版本查询已有 job。
     * @Logic: 创建任务前做幂等检查，避免同一 chunk/version 重复排队。
     * @Param: chunkUid 切片唯一标识；dictionaryVersion 词库版本。
     * @Return: 已存在 job；不存在时为 null。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    ReportChunkTagJob selectByChunkUidAndVersion(@Param("chunkUid") String chunkUid, @Param("dictionaryVersion") String dictionaryVersion);

    /**
     * @Description: 查询到期可执行的标签抽取 job。
     * @Logic: 按 PENDING/FAILED 且 nextRunAt 到期过滤，并限制批量大小。
     * @Param: now 当前时间；limit 批量上限。
     * @Return: 待执行 job 列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    List<ReportChunkTagJob> selectRunnableJobs(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * @Description: 将标签抽取 job 标记为执行中。
     * @Logic: 调度领取任务后更新状态、开始时间和尝试次数。
     * @Param: id job 主键；now 当前时间。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    int markProcessing(@Param("id") Long id, @Param("now") Instant now);

    /**
     * @Description: 将标签抽取 job 标记为成功。
     * @Logic: 标签落库完成后更新状态和结束时间。
     * @Param: id job 主键；now 当前时间。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    int markSucceeded(@Param("id") Long id, @Param("now") Instant now);

    /**
     * @Description: 将标签抽取 job 标记为失败。
     * @Logic: 记录失败摘要并设置下次重试时间，超过次数后保持 FAILED 供人工诊断。
     * @Param: id job 主键；lastError 失败原因；nextRunAt 下次执行时间；now 当前时间。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    int markFailed(@Param("id") Long id, @Param("lastError") String lastError, @Param("nextRunAt") Instant nextRunAt, @Param("now") Instant now);
}
