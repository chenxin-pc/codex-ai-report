package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportVectorMetadataSyncJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * @Description: Milvus metadata 同步 job Mapper，负责 sync job 创建、领取、成功和失败状态流转。
 * @Logic: metadata 同步服务通过该 Mapper 保证 MySQL 标签主数据最终同步到向量检索索引。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: job 实体列表或受影响行数。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Mapper
public interface ReportVectorMetadataSyncJobMapper {

    /**
     * @Description: 插入 metadata 同步 job。
     * @Logic: 标签抽取成功后基于标签快照哈希创建 PENDING 同步任务。
     * @Param: job metadata 同步 job 实体。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    int insert(ReportVectorMetadataSyncJob job);

    /**
     * @Description: 按 chunk 和标签快照哈希查询已有同步 job。
     * @Logic: 创建同步任务前做幂等检查，避免同一标签快照重复排队。
     * @Param: chunkUid 切片唯一标识；tagSnapshotHash 标签快照哈希。
     * @Return: 已存在 job；不存在时为 null。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    ReportVectorMetadataSyncJob selectByChunkUidAndHash(@Param("chunkUid") String chunkUid, @Param("tagSnapshotHash") String tagSnapshotHash);

    /**
     * @Description: 查询到期可执行的 metadata 同步 job。
     * @Logic: 按 PENDING/FAILED 且 nextRunAt 到期过滤，并限制批量大小。
     * @Param: now 当前时间；limit 批量上限。
     * @Return: 待执行 job 列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    List<ReportVectorMetadataSyncJob> selectRunnableJobs(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * @Description: 将 metadata 同步 job 标记为执行中。
     * @Logic: 调度领取任务后更新状态、开始时间和尝试次数。
     * @Param: id job 主键；now 当前时间。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    int markProcessing(@Param("id") Long id, @Param("now") Instant now);

    /**
     * @Description: 将 metadata 同步 job 标记为成功。
     * @Logic: Milvus metadata 写入完成后更新状态和结束时间。
     * @Param: id job 主键；now 当前时间。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    int markSucceeded(@Param("id") Long id, @Param("now") Instant now);

    /**
     * @Description: 将 metadata 同步 job 标记为失败。
     * @Logic: 记录失败摘要并设置下次重试时间，保留 MySQL 标签主数据不回滚。
     * @Param: id job 主键；lastError 失败原因；nextRunAt 下次执行时间；now 当前时间。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    int markFailed(@Param("id") Long id, @Param("lastError") String lastError, @Param("nextRunAt") Instant nextRunAt, @Param("now") Instant now);
}
