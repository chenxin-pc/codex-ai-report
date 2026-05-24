package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 子切片标签抽取任务实体，管理新入库打标、失败重试、历史重打标和词库版本重算。
 * @Logic: 调度服务按状态和 nextRunAt 拉取任务，执行成功后写入 report_chunk_tag 并触发 metadata 同步任务。
 * @Param: 详见字段注释；该类型本身无入参。
 * @Return: 作为标签抽取 job 状态流转和持久化的数据载体返回。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Getter
@Setter
public class ReportChunkTagJob {

    /** job 表主键。 */
    private Long id;
    /** job 唯一标识，用于日志、幂等和前端观测。 */
    private String jobUid;
    /** 待打标切片所属研报 ID。 */
    private Long reportId;
    /** 待打标切片唯一标识。 */
    private String chunkUid;
    /** 本次打标使用的词库版本。 */
    private String dictionaryVersion;
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
