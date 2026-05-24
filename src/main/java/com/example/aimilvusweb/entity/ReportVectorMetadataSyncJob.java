package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 向量 metadata 同步任务实体，管理 MySQL 标签主数据到 Milvus metadata 的最终一致同步。
 * @Logic: 标签抽取成功后创建该任务，调度服务读取最新标签快照并更新或重建 Milvus 中的向量文档 metadata。
 * @Param: 详见字段注释；该类型本身无入参。
 * @Return: 作为 Milvus metadata sync job 状态流转和持久化的数据载体返回。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Getter
@Setter
public class ReportVectorMetadataSyncJob {

    /** job 表主键。 */
    private Long id;
    /** job 唯一标识，用于日志、幂等和观测。 */
    private String jobUid;
    /** 待同步切片所属研报 ID。 */
    private Long reportId;
    /** 待同步切片唯一标识。 */
    private String chunkUid;
    /** metadata 版本，便于未来变更字段结构时重建。 */
    private String metadataVersion;
    /** 标签快照哈希，用于判断 Milvus metadata 是否需要重复同步。 */
    private String tagSnapshotHash;
    /** job 当前状态，例如 PENDING、PROCESSING、SUCCEEDED 或 FAILED。 */
    private String status;
    /** 已执行次数，用于重试控制。 */
    private Integer attemptCount;
    /** 最大重试次数，超过后不再自动调度。 */
    private Integer maxAttempts;
    /** 最近一次失败原因摘要。 */
    private String lastError;
    /** 下次允许调度执行的时间。 */
    private Instant nextRunAt;
    /** 最近一次开始执行时间。 */
    private Instant startedAt;
    /** 最近一次结束执行时间。 */
    private Instant finishedAt;
    /** job 创建时间。 */
    private Instant createdAt;
    /** job 更新时间。 */
    private Instant updatedAt;
}
