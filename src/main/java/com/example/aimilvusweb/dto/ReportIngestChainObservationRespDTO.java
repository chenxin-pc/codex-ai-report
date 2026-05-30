package com.example.aimilvusweb.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * @Description: 报告导入链路解释响应，面向开发者展示导入各阶段的状态、数据变化、影响点和按需明细。
 * @Logic: 顶层保存报告摘要和阶段列表；阶段节点再拆分为摘要、解释和明细，避免前端直接拼接多张底层表。
 * @Param: report 报告摘要；overallStatus 整体状态；currentStage 当前阶段；failedStage 失败阶段；lastErrorCode 最后错误码；lastErrorMessageShort 最后错误摘要；updatedAt 更新时间；stages 阶段解释列表。
 * @Return: 无（record 数据载体）。
 * @author: cx
 * @Date: 2026-05-30 00:00:00
 */
public record ReportIngestChainObservationRespDTO(
        /** 报告主档和任务摘要，用于详情页顶部展示。 */
        ReportSummaryRespDTO report,
        /** 整体链路状态，例如 SUCCEEDED、FAILED、PROCESSING 或 NOT_STARTED。 */
        String overallStatus,
        /** 当前最需要关注的阶段，优先为失败阶段，其次为未完成阶段。 */
        String currentStage,
        /** 失败阶段；无失败时为空字符串。 */
        String failedStage,
        /** 最近一次错误码；无错误时为空字符串。 */
        String lastErrorCode,
        /** 最近一次错误摘要；无错误时为空字符串。 */
        String lastErrorMessageShort,
        /** 观测数据更新时间，优先来自任务更新时间或阶段事件时间。 */
        Instant updatedAt,
        /** 按导入顺序排列的阶段解释列表。 */
        List<StageRespDTO> stages
) {
    /**
     * @Description: 报告和任务摘要。
     * @Logic: 汇总 report_document 与最新 ingest_job 的关键字段，作为链路详情入口信息。
     * @Param: reportId 报告ID；title 标题；source 来源；institution 机构；publishDate 发布日期；createdAt 创建时间；jobId 任务ID；ocrStatus/chunkStatus/vectorStatus 三阶段状态。
     * @Return: 无（record 数据载体）。
     */
    public record ReportSummaryRespDTO(
            /** 报告主键。 */
            Long reportId,
            /** 报告标题。 */
            String title,
            /** 报告来源。 */
            String source,
            /** 发布机构。 */
            String institution,
            /** 发布日期。 */
            LocalDate publishDate,
            /** 报告主档创建时间。 */
            Instant createdAt,
            /** 异步导入任务ID。 */
            String jobId,
            /** OCR 阶段最新状态。 */
            String ocrStatus,
            /** CHUNK 阶段最新状态。 */
            String chunkStatus,
            /** VECTOR 阶段最新状态。 */
            String vectorStatus
    ) {
    }

    /**
     * @Description: 单个导入阶段的解释节点。
     * @Logic: 保存阶段状态、摘要、解释和明细数据，供前端通过步骤导航切换展示。
     * @Param: stage 阶段编码；name 展示名称；status 状态；durationMs 耗时；modelName 模型；attempt 尝试次数；errorCode/errorMessageShort 错误信息；summary 摘要；explanation 解释；details 明细。
     * @Return: 无（record 数据载体）。
     */
    public record StageRespDTO(
            /** 阶段编码，例如 UPLOAD、OCR、CHUNK、VECTOR。 */
            String stage,
            /** 阶段展示名称。 */
            String name,
            /** 阶段状态。 */
            String status,
            /** 最近阶段事件耗时毫秒数。 */
            Long durationMs,
            /** 阶段使用的模型名称。 */
            String modelName,
            /** 最近阶段事件尝试次数。 */
            Integer attempt,
            /** 阶段错误码。 */
            String errorCode,
            /** 阶段错误摘要。 */
            String errorMessageShort,
            /** 阶段摘要指标。 */
            StageSummaryRespDTO summary,
            /** 阶段解释文本和读写影响。 */
            StageExplanationRespDTO explanation,
            /** 阶段按需展开明细。 */
            StageDetailsRespDTO details
    ) {
    }

    /**
     * @Description: 阶段摘要数据。
     * @Logic: 使用少量指标表达阶段输入输出，不默认暴露大文本。
     * @Param: inputLabel 输入摘要；outputLabel 输出摘要；metrics 指标列表。
     * @Return: 无（record 数据载体）。
     */
    public record StageSummaryRespDTO(
            /** 阶段输入摘要。 */
            String inputLabel,
            /** 阶段输出摘要。 */
            String outputLabel,
            /** 可视化指标列表。 */
            List<MetricRespDTO> metrics
    ) {
    }

    /**
     * @Description: 阶段解释数据。
     * @Logic: 使用人可读文本解释操作前后状态、读写对象和后续影响。
     * @Param: operationSummary 操作摘要；beforeState 执行前状态；afterState 执行后状态；reads 读取对象；writes 写入对象；impact 影响点；failureExplanation 失败说明。
     * @Return: 无（record 数据载体）。
     */
    public record StageExplanationRespDTO(
            /** 当前阶段做了什么。 */
            String operationSummary,
            /** 执行前数据状态。 */
            List<String> beforeState,
            /** 执行后数据状态。 */
            List<String> afterState,
            /** 阶段读取的数据或外部资源。 */
            List<String> reads,
            /** 阶段写入或更新的数据。 */
            List<String> writes,
            /** 对后续阶段的影响。 */
            String impact,
            /** 失败时的解释和排障方向。 */
            String failureExplanation
    ) {
    }

    /**
     * @Description: 阶段明细数据。
     * @Logic: 保存 OCR、段落、PARENT-CHILD、过滤诊断和向量候选的可展开明细。
     * @Param: ocrPages OCR 页明细；paragraphAtoms 段落 atom 明细；parentChunks PARENT-CHILD 树；filteredDiagnostics 过滤诊断；vectorCandidates 向量候选。
     * @Return: 无（record 数据载体）。
     */
    public record StageDetailsRespDTO(
            /** OCR 页级预览明细。 */
            List<OcrPageDetailRespDTO> ocrPages,
            /** 段落 atom 预览明细。 */
            List<ParagraphAtomDetailRespDTO> paragraphAtoms,
            /** PARENT-CHILD 树。 */
            List<ParentChunkRespDTO> parentChunks,
            /** 被过滤或未落库诊断明细。 */
            List<ChunkDiagnosticRespDTO> filteredDiagnostics,
            /** 向量入库候选 CHILD 明细。 */
            List<VectorCandidateRespDTO> vectorCandidates
    ) {
    }

    /**
     * @Description: 通用指标项。
     * @Logic: label/value 用于展示指标，tone 用于前端标识成功、警告或危险状态。
     * @Param: label 指标名称；value 指标值；tone 展示语气。
     * @Return: 无（record 数据载体）。
     */
    public record MetricRespDTO(String label, String value, String tone) {
    }

    /**
     * @Description: OCR 页级预览明细。
     * @Logic: 只返回短预览和诊断摘要，避免默认返回完整 OCR 原文。
     * @Param: pageNumber 页码；rawPreview 原文预览；cleanedPreview 清洗文本预览；diagnosticsPreview 诊断预览。
     * @Return: 无（record 数据载体）。
     */
    public record OcrPageDetailRespDTO(Integer pageNumber, String rawPreview, String cleanedPreview, String diagnosticsPreview) {
    }

    /**
     * @Description: 段落 atom 预览明细。
     * @Logic: 保存段落定位、token 和文本预览，用于 OCR 阶段展开查看。
     * @Param: paragraphId 段落ID；pageNumber 页码；sectionPath 章节路径；tokenCount token数；textPreview 文本预览；diagnosticsPreview 诊断预览。
     * @Return: 无（record 数据载体）。
     */
    public record ParagraphAtomDetailRespDTO(Integer paragraphId, Integer pageNumber, String sectionPath,
                                             Integer tokenCount, String textPreview, String diagnosticsPreview) {
    }

    /**
     * @Description: PARENT chunk 节点。
     * @Logic: 一个 PARENT 下包含多个 CHILD，并带有向量化统计，表示语义切片最终结构。
     * @Param: chunkUid PARENT UID；chunkIndex 索引；sectionPath 章节；tokenCount token数；pageRange 页码范围；paragraphRange 段落范围；childCount CHILD数量；vectorStoredCount 已向量化CHILD数；textPreview 文本预览；children CHILD列表。
     * @Return: 无（record 数据载体）。
     */
    public record ParentChunkRespDTO(String chunkUid, Integer chunkIndex, String sectionPath, Integer tokenCount,
                                     String pageRange, String paragraphRange, int childCount, int vectorStoredCount,
                                     String textPreview, List<ChildChunkRespDTO> children) {
    }

    /**
     * @Description: CHILD chunk 节点。
     * @Logic: 保存 CHILD 与 PARENT 的定位关系和向量状态，供 CHUNK 与 VECTOR 阶段共享展示。
     * @Param: chunkUid CHILD UID；parentChunkUid PARENT UID；chunkIndex 索引；sectionPath 章节；tokenCount token数；pageRange 页码范围；paragraphRange 段落范围；vectorStored 是否已入向量；textPreview 文本预览。
     * @Return: 无（record 数据载体）。
     */
    public record ChildChunkRespDTO(String chunkUid, String parentChunkUid, Integer chunkIndex, String sectionPath,
                                    Integer tokenCount, String pageRange, String paragraphRange, boolean vectorStored,
                                    String textPreview) {
    }

    /**
     * @Description: 切片诊断明细。
     * @Logic: 展示过滤项或未落库候选，明确其不会进入向量入库候选。
     * @Param: chunkUid 切片UID；parentChunkUid 父UID；chunkType 类型；parentIndex 父索引；chunkIndexInParent 子索引；kept 是否保留；persisted 是否已落库；filterReason 过滤原因；diagnosticsPreview 诊断预览；pageRange 页码范围；paragraphRange 段落范围；tokenCount token数；textPreview 文本预览。
     * @Return: 无（record 数据载体）。
     */
    public record ChunkDiagnosticRespDTO(String chunkUid, String parentChunkUid, String chunkType, Integer parentIndex,
                                         Integer chunkIndexInParent, boolean kept, boolean persisted, String filterReason,
                                         String diagnosticsPreview, String pageRange, String paragraphRange,
                                         Integer tokenCount, String textPreview) {
    }

    /**
     * @Description: 向量入库候选 CHILD 明细。
     * @Logic: 指向 CHUNK 阶段的 CHILD 节点，表示向量阶段消费的输入和当前入库状态。
     * @Param: chunkUid CHILD UID；parentChunkUid PARENT UID；parentSectionPath PARENT章节；sectionPath CHILD章节；tokenCount token数；pageRange 页码范围；vectorStored 是否已入向量；status 展示状态。
     * @Return: 无（record 数据载体）。
     */
    public record VectorCandidateRespDTO(String chunkUid, String parentChunkUid, String parentSectionPath,
                                         String sectionPath, Integer tokenCount, String pageRange,
                                         boolean vectorStored, String status) {
    }
}
