package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * @Description: 异步导入任务实体，保存上传后的任务状态、文件路径和重试调度信息。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:15:00
 */
@Getter
@Setter
public class IngestJob {

    /** 任务自增主键。 */
    private Long id;
    /** 任务全局唯一标识，用于外部查询与链路追踪。 */
    private String jobUid;
    /** 已绑定的报告主键，OCR 成功后回填。 */
    private Long reportId;
    /** 提交时的标题快照，用于观测展示。 */
    private String reportTitleSnapshot;
    /** 标题标准化检索键，用于模糊搜索。 */
    private String titleSearchKey;
    /** 报告来源。 */
    private String source;
    /** 报告机构。 */
    private String institution;
    /** 报告发布日期。 */
    private LocalDate publishDate;
    /** 导入时显式指定的主题标签，逗号分隔。 */
    private String themeTags;
    /** 导入时显式指定的行业标签，逗号分隔。 */
    private String industryTags;
    /** 导入时显式指定的公司标签，逗号分隔。 */
    private String companyTags;
    /** 导入时显式指定的股票代码标签，逗号分隔。 */
    private String tickerTags;
    /** 导入时显式指定的研报作者，逗号或顿号分隔。 */
    private String authorTags;
    /** 上传原始文件名。 */
    private String originalFilename;
    /** 上传文件落盘绝对路径。 */
    private String filePath;
    /** OCR 阶段状态。 */
    private String ocrStatus;
    /** Chunk 阶段状态。 */
    private String chunkStatus;
    /** Vector 阶段状态。 */
    private String vectorStatus;
    /** OCR 阶段尝试次数。 */
    private Integer ocrAttemptCount;
    /** Chunk 阶段尝试次数。 */
    private Integer chunkAttemptCount;
    /** Vector 阶段尝试次数。 */
    private Integer vectorAttemptCount;
    /** 任务下次可执行时间。 */
    private Instant nextRunAt;
    /** 调度优先级，数值越大优先级越高。 */
    private Integer priority;
    /** 最近一次错误码。 */
    private String lastErrorCode;
    /** 最近一次错误摘要。 */
    private String lastErrorMessage;
    /** 任务创建时间。 */
    private Instant createdAt;
    /** 任务更新时间。 */
    private Instant updatedAt;
}
