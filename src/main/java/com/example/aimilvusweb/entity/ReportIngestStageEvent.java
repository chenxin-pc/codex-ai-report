package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 导入阶段事件实体，记录阶段耗时、模型、输入输出规模与失败摘要。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:15:00
 */
@Getter
@Setter
public class ReportIngestStageEvent {

    /** 事件自增主键。 */
    private Long id;
    /** 关联任务唯一标识。 */
    private String jobUid;
    /** 关联报告主键。 */
    private Long reportId;
    /** 事件记录时的标题快照。 */
    private String reportTitleSnapshot;
    /** 标题标准化检索键。 */
    private String titleSearchKey;
    /** 阶段名称（OCR/CHUNK/VECTOR）。 */
    private String stage;
    /** 阶段执行尝试次数。 */
    private Integer attempt;
    /** 阶段状态。 */
    private String status;
    /** 当前阶段调用模型名。 */
    private String modelName;
    /** 阶段输入规模。 */
    private Integer inputSize;
    /** 阶段输出规模。 */
    private Integer outputSize;
    /** 阶段开始时间。 */
    private Instant startedAt;
    /** 阶段结束时间。 */
    private Instant finishedAt;
    /** 阶段耗时（毫秒）。 */
    private Long durationMs;
    /** 错误码。 */
    private String errorCode;
    /** 错误摘要。 */
    private String errorMessageShort;
    /** 跟踪 ID，用于串联日志和事件。 */
    private String traceId;
    /** 事件创建时间。 */
    private Instant createdAt;
}
