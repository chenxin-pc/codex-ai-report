package com.example.aimilvusweb.service.quality;

import com.example.aimilvusweb.dto.ReportChunkObservationRespDTO;
import com.example.aimilvusweb.dto.ReportIngestChainObservationRespDTO;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportChunkDiagnostic;
import com.example.aimilvusweb.entity.ReportDocument;
import com.example.aimilvusweb.entity.ReportIngestStageEvent;
import com.example.aimilvusweb.entity.ReportOcrPage;
import com.example.aimilvusweb.entity.ReportParagraphAtom;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import com.example.aimilvusweb.repository.ReportChunkDiagnosticMapper;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportIngestStageEventMapper;
import com.example.aimilvusweb.repository.ReportOcrPageMapper;
import com.example.aimilvusweb.repository.ReportParagraphAtomMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
/**
 * @Description: ReportQualityQueryService类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class ReportQualityQueryService {

    private static final String STAGE_UPLOAD = "UPLOAD";
    private static final String STAGE_OCR = "OCR";
    private static final String STAGE_CHUNK = "CHUNK";
    private static final String STAGE_VECTOR = "VECTOR";
    private static final int PREVIEW_LENGTH = 180;

    private final ReportOcrPageMapper reportOcrPageMapper;
    private final ReportParagraphAtomMapper reportParagraphAtomMapper;
    private final ReportChunkMapper reportChunkMapper;
    private final ReportChunkDiagnosticMapper reportChunkDiagnosticMapper;
    private final ReportDocumentMapper reportDocumentMapper;
    private final ReportIngestStageEventMapper reportIngestStageEventMapper;

    /**
     * @Description: 初始化ReportQualityQueryService依赖与运行所需组件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    @Autowired
    public ReportQualityQueryService(ReportOcrPageMapper reportOcrPageMapper,
                                     ReportParagraphAtomMapper reportParagraphAtomMapper,
                                     ReportChunkMapper reportChunkMapper,
                                     ReportChunkDiagnosticMapper reportChunkDiagnosticMapper,
                                     ReportDocumentMapper reportDocumentMapper,
                                     ReportIngestStageEventMapper reportIngestStageEventMapper) {
        this.reportOcrPageMapper = reportOcrPageMapper;
        this.reportParagraphAtomMapper = reportParagraphAtomMapper;
        this.reportChunkMapper = reportChunkMapper;
        this.reportChunkDiagnosticMapper = reportChunkDiagnosticMapper;
        this.reportDocumentMapper = reportDocumentMapper;
        this.reportIngestStageEventMapper = reportIngestStageEventMapper;
    }

    /**
     * @Description: 兼容旧测试的质量查询服务构造器。
     * @Logic: 未提供阶段事件 Mapper 时仍可查询质量数据和切片对照；链路解释接口会按无阶段事件降级展示。
     * @Param: reportOcrPageMapper OCR页 Mapper；reportParagraphAtomMapper 段落 Mapper；reportChunkMapper chunk Mapper；reportChunkDiagnosticMapper 诊断 Mapper；reportDocumentMapper 报告 Mapper。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 00:00:00
     */
    public ReportQualityQueryService(ReportOcrPageMapper reportOcrPageMapper,
                                     ReportParagraphAtomMapper reportParagraphAtomMapper,
                                     ReportChunkMapper reportChunkMapper,
                                     ReportChunkDiagnosticMapper reportChunkDiagnosticMapper,
                                     ReportDocumentMapper reportDocumentMapper) {
        this(reportOcrPageMapper, reportParagraphAtomMapper, reportChunkMapper,
                reportChunkDiagnosticMapper, reportDocumentMapper, null);
    }

    /**
     * @Description: 查询面向开发者的导入链路解释视图。
     * @Logic: 按 reportId 聚合报告主档、阶段事件、OCR页、段落atom、chunk和切片诊断；再组装上传、OCR、语义切片和向量入库阶段解释。
     * @Param: reportId 研报ID。
     * @Return: 导入链路解释响应，包含报告摘要、整体状态、阶段解释和按需明细。
     * @author: cx
     * @Date: 2026-05-30 00:00:00
     */
    public ReportIngestChainObservationRespDTO getIngestChainObservation(Long reportId) {
        if (reportId == null) {
            throw new IllegalArgumentException("reportId is required");
        }
        ReportDocument reportDocument = reportDocumentMapper.selectById(reportId);
        if (reportDocument == null) {
            throw new IllegalArgumentException("Report not found: " + reportId);
        }
        List<ReportIngestStageEvent> stageEvents = selectStageEvents(reportId);
        List<ReportOcrPage> ocrPages = safeList(reportOcrPageMapper.selectByReportId(reportId));
        List<ReportParagraphAtom> paragraphAtoms = safeList(reportParagraphAtomMapper.selectByReportId(reportId));
        List<ReportChunk> chunks = safeList(reportChunkMapper.selectByReportId(reportId));
        List<ReportChunkDiagnostic> diagnostics = safeList(reportChunkDiagnosticMapper.selectByReportId(reportId));

        Map<String, ReportIngestStageEvent> latestEvents = latestEventsByStage(stageEvents);
        List<ReportIngestChainObservationRespDTO.ParentChunkRespDTO> parentTree = buildParentTree(chunks);
        List<ReportIngestChainObservationRespDTO.ChunkDiagnosticRespDTO> filteredDiagnostics = buildFilteredDiagnostics(chunks, diagnostics);
        List<ReportIngestChainObservationRespDTO.VectorCandidateRespDTO> vectorCandidates = buildVectorCandidates(chunks);

        List<ReportIngestChainObservationRespDTO.StageRespDTO> stages = List.of(
                buildUploadStage(reportDocument, latestEvents),
                buildOcrStage(latestEvents.get(STAGE_OCR), ocrPages, paragraphAtoms),
                buildChunkStage(latestEvents.get(STAGE_CHUNK), chunks, diagnostics, parentTree, filteredDiagnostics),
                buildVectorStage(latestEvents.get(STAGE_VECTOR), vectorCandidates)
        );
        String failedStage = resolveFailedStage(stages);
        String currentStage = failedStage.isBlank() ? resolveCurrentStage(stages) : failedStage;
        ReportIngestStageEvent latestEvent = latestStageEvent(stageEvents);
        return new ReportIngestChainObservationRespDTO(
                new ReportIngestChainObservationRespDTO.ReportSummaryRespDTO(
                        reportId,
                        safeText(reportDocument.getTitle()),
                        safeText(reportDocument.getSource()),
                        safeText(reportDocument.getInstitution()),
                        reportDocument.getPublishDate(),
                        reportDocument.getCreatedAt(),
                        latestEvent == null ? "" : safeText(latestEvent.getJobUid()),
                        statusOf(latestEvents.get(STAGE_OCR), !ocrPages.isEmpty()),
                        statusOf(latestEvents.get(STAGE_CHUNK), hasChunkData(chunks, diagnostics)),
                        statusOf(latestEvents.get(STAGE_VECTOR), hasAnyVectorStored(chunks))
                ),
                resolveOverallStatus(stages),
                currentStage,
                failedStage,
                latestEvent == null ? "" : safeText(latestEvent.getErrorCode()),
                latestEvent == null ? "" : safeText(latestEvent.getErrorMessageShort()),
                latestEvent == null ? reportDocument.getCreatedAt() : latestEvent.getCreatedAt(),
                stages
        );
    }

    /**
     * @Description: 返回ByReportId字段当前值。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public ReportQualityData getByReportId(Long reportId) {
        if (reportId == null) {
            throw new IllegalArgumentException("reportId is required");
        }
        return new ReportQualityData(
                reportOcrPageMapper.selectByReportId(reportId),
                reportParagraphAtomMapper.selectByReportId(reportId),
                reportChunkMapper.selectByReportId(reportId),
                reportChunkDiagnosticMapper.selectByReportId(reportId)
        );
    }

    /**
     * @Description: 查询切片前后对照数据。
     * @Logic: 读取段落原子与切片结果，按段落范围拼接切片前原文并与切片文本配对返回。
     * @Param: reportId 研报ID。
     * @Return: 切片观测响应。
     * @author: cx
     * @Date: 2026-05-20 23:40:00
     */
    public ReportChunkObservationRespDTO getChunkObservationByReportId(Long reportId) {
        if (reportId == null) {
            throw new IllegalArgumentException("reportId is required");
        }
        ReportDocument reportDocument = reportDocumentMapper.selectById(reportId);
        List<ReportParagraphAtom> paragraphAtoms = reportParagraphAtomMapper.selectByReportId(reportId);
        List<ReportChunkDiagnostic> diagnostics = reportChunkDiagnosticMapper.selectByReportId(reportId);
        Map<Integer, ReportParagraphAtom> paragraphMap = paragraphAtoms.stream()
                .filter(item -> item.getParagraphId() != null)
                .collect(Collectors.toMap(ReportParagraphAtom::getParagraphId, item -> item, (left, right) -> left));
        Map<String, ReportChunkDiagnostic> diagnosticMap = new HashMap<>();
        for (ReportChunkDiagnostic diagnostic : diagnostics) {
            if (diagnostic.getChunkUid() == null || diagnostic.getChunkUid().isBlank()) {
                continue;
            }
            diagnosticMap.putIfAbsent(diagnostic.getChunkUid(), diagnostic);
        }
        List<ReportChunkObservationRespDTO.ChunkPairRespDTO> pairs = deduplicateChildChunks(reportChunkMapper.selectByReportId(reportId)).stream()
                .sorted(Comparator.comparing(ReportChunk::getChunkIndex, Comparator.nullsLast(Integer::compareTo)))
                .map(chunk -> {
                    String sourceText = resolveSourceParagraphText(chunk, paragraphMap);
                    String chunkText = chunk.getChunkText() == null ? "" : chunk.getChunkText();
                    boolean sameContent = normalizeForCompare(sourceText).equals(normalizeForCompare(chunkText));
                    ReportChunkDiagnostic diagnostic = diagnosticMap.get(chunk.getChunkUid());
                    String filterReason = diagnostic == null ? chunk.getFilterReason() : diagnostic.getFilterReason();
                    return new ReportChunkObservationRespDTO.ChunkPairRespDTO(
                            chunk.getChunkIndex(),
                            chunk.getChunkUid(),
                            chunk.getChunkType(),
                            chunk.getSectionPath(),
                            chunk.getStartParagraphId(),
                            chunk.getEndParagraphId(),
                            chunk.getStartPageNumber(),
                            chunk.getEndPageNumber(),
                            chunk.getTokenCount(),
                            filterReason,
                            sameContent,
                            sameContent ? "仅边界切分，无文本改写" : "存在文本差异（可能包含格式整理、过滤或重叠策略）",
                            sourceText,
                            chunkText);
                })
                .toList();
        String reportTitle = reportDocument == null ? null : reportDocument.getTitle();
        return new ReportChunkObservationRespDTO(reportId, reportTitle, pairs.size(), pairs);
    }

    /**
     * @Description: 查询阶段事件列表。
     * @Logic: 阶段事件 Mapper 为空时返回空集合，保证旧测试和局部构造不会影响链路解释其它数据。
     * @Param: reportId 报告ID。
     * @Return: 阶段事件列表。
     * @author: cx
     * @Date: 2026-05-30 00:00:00
     */
    private List<ReportIngestStageEvent> selectStageEvents(Long reportId) {
        if (reportIngestStageEventMapper == null) {
            return List.of();
        }
        return safeList(reportIngestStageEventMapper.selectTimelineByReportId(reportId));
    }

    /**
     * @Description: 按阶段选择最新事件。
     * @Logic: 按事件创建时间和开始时间保留每个阶段最新记录，用于阶段状态与错误摘要展示。
     * @Param: events 阶段事件列表。
     * @Return: 阶段编码到最新事件的映射。
     * @author: cx
     * @Date: 2026-05-30 00:00:00
     */
    private Map<String, ReportIngestStageEvent> latestEventsByStage(List<ReportIngestStageEvent> events) {
        Map<String, ReportIngestStageEvent> latestEvents = new LinkedHashMap<>();
        for (ReportIngestStageEvent event : safeList(events)) {
            if (event == null || event.getStage() == null || event.getStage().isBlank()) {
                continue;
            }
            ReportIngestStageEvent previous = latestEvents.get(event.getStage());
            if (previous == null || compareEventTime(event, previous) >= 0) {
                latestEvents.put(event.getStage(), event);
            }
        }
        return latestEvents;
    }

    /**
     * @Description: 选择最后产生的阶段事件。
     * @Logic: 用创建时间、完成时间和开始时间比较事件新旧，全部为空时保持列表后者优先。
     * @Param: events 阶段事件列表。
     * @Return: 最新阶段事件；没有事件时返回 null。
     * @author: cx
     * @Date: 2026-05-30 00:00:00
     */
    private ReportIngestStageEvent latestStageEvent(List<ReportIngestStageEvent> events) {
        ReportIngestStageEvent latestEvent = null;
        for (ReportIngestStageEvent event : safeList(events)) {
            if (event == null) {
                continue;
            }
            if (latestEvent == null || compareEventTime(event, latestEvent) >= 0) {
                latestEvent = event;
            }
        }
        return latestEvent;
    }

    private int compareEventTime(ReportIngestStageEvent left, ReportIngestStageEvent right) {
        return eventTime(left).compareTo(eventTime(right));
    }

    private Instant eventTime(ReportIngestStageEvent event) {
        if (event.getCreatedAt() != null) {
            return event.getCreatedAt();
        }
        if (event.getFinishedAt() != null) {
            return event.getFinishedAt();
        }
        if (event.getStartedAt() != null) {
            return event.getStartedAt();
        }
        return Instant.EPOCH;
    }

    /**
     * @Description: 构建上传阶段解释。
     * @Logic: 上传阶段没有独立事件时以 report_document 是否存在作为成功信号。
     * @Param: reportDocument 报告主档；latestEvents 各阶段最新事件。
     * @Return: 上传阶段解释节点。
     * @author: cx
     * @Date: 2026-05-30 00:00:00
     */
    private ReportIngestChainObservationRespDTO.StageRespDTO buildUploadStage(ReportDocument reportDocument,
                                                                              Map<String, ReportIngestStageEvent> latestEvents) {
        String status = reportDocument.getId() == null ? "NOT_STARTED" : "SUCCEEDED";
        return new ReportIngestChainObservationRespDTO.StageRespDTO(
                STAGE_UPLOAD,
                "上传与任务登记",
                status,
                null,
                "",
                null,
                "",
                "",
                summary("PDF 文件与元信息", "报告主档已创建", List.of(
                        metric("reportId", String.valueOf(reportDocument.getId()), "success"),
                        metric("后续阶段", latestEvents.isEmpty() ? "等待调度" : "已有阶段事件", latestEvents.isEmpty() ? "warning" : "success")
                )),
                explanation(
                        "接收上传文件和标题、来源、机构、发布日期等元信息，创建或绑定报告主档，并为后续异步阶段提供任务上下文。",
                        List.of("上传文件已通过基础校验", "任务携带报告元信息和文件路径"),
                        List.of("report_document 可作为后续 OCR、切片和向量入库的主线", "后续阶段将通过 reportId 关联质量数据"),
                        List.of("上传文件", "上传表单元信息"),
                        List.of("report_document", "ingest_job"),
                        "OCR 阶段会读取任务中的文件路径并绑定 reportId。",
                        ""
                ),
                emptyDetails()
        );
    }

    private ReportIngestChainObservationRespDTO.StageRespDTO buildOcrStage(ReportIngestStageEvent event,
                                                                           List<ReportOcrPage> pages,
                                                                           List<ReportParagraphAtom> atoms) {
        return new ReportIngestChainObservationRespDTO.StageRespDTO(
                STAGE_OCR,
                "OCR 文本解析",
                statusOf(event, !pages.isEmpty() || !atoms.isEmpty()),
                event == null ? null : event.getDurationMs(),
                event == null ? "" : safeText(event.getModelName()),
                event == null ? null : event.getAttempt(),
                event == null ? "" : safeText(event.getErrorCode()),
                event == null ? "" : safeText(event.getErrorMessageShort()),
                summary("PDF 文件", "页级 OCR 与段落 atom", List.of(
                        metric("OCR 页", String.valueOf(pages.size()), pages.isEmpty() ? "warning" : "success"),
                        metric("段落 atom", String.valueOf(atoms.size()), atoms.isEmpty() ? "warning" : "success")
                )),
                explanation(
                        "读取上传 PDF，调用 OCR 服务，保留页级原文和清洗文本，并将清洗后内容拆成可被切片使用的段落 atom。",
                        List.of("任务中存在文件路径", "reportId 已可用于关联 OCR 结果"),
                        List.of("report_ocr_page 记录页级 OCR 结果", "report_paragraph_atom 记录段落级输入"),
                        List.of("上传 PDF", "report_document"),
                        List.of("report_ocr_page", "report_paragraph_atom", "ingest_job.report_id"),
                        "CHUNK 阶段依赖 paragraph atom 生成 PARENT/CHILD；如果 OCR 无文本，后续阶段不应继续。",
                        event == null || safeText(event.getErrorCode()).isBlank() ? "" : "OCR 失败会阻断语义切片，请优先检查文件可读性、OCR 服务和错误摘要。"
                ),
                new ReportIngestChainObservationRespDTO.StageDetailsRespDTO(
                        pages.stream().map(this::ocrPageDetail).toList(),
                        atoms.stream().map(this::paragraphAtomDetail).toList(),
                        List.of(),
                        List.of(),
                        List.of()
                )
        );
    }

    private ReportIngestChainObservationRespDTO.StageRespDTO buildChunkStage(ReportIngestStageEvent event,
                                                                             List<ReportChunk> chunks,
                                                                             List<ReportChunkDiagnostic> diagnostics,
                                                                             List<ReportIngestChainObservationRespDTO.ParentChunkRespDTO> parentTree,
                                                                             List<ReportIngestChainObservationRespDTO.ChunkDiagnosticRespDTO> filteredDiagnostics) {
        long parentCount = chunks.stream().filter(chunk -> "PARENT".equals(chunk.getChunkType())).count();
        long childCount = chunks.stream().filter(chunk -> "CHILD".equals(chunk.getChunkType())).count();
        long keptDiagnostics = diagnostics.stream().filter(diagnostic -> Boolean.TRUE.equals(diagnostic.getKept())).count();
        long filteredCount = diagnostics.stream().filter(diagnostic -> Boolean.FALSE.equals(diagnostic.getKept())).count();
        return new ReportIngestChainObservationRespDTO.StageRespDTO(
                STAGE_CHUNK,
                "语义切片",
                statusOf(event, hasChunkData(chunks, diagnostics)),
                event == null ? null : event.getDurationMs(),
                event == null ? "" : safeText(event.getModelName()),
                event == null ? null : event.getAttempt(),
                event == null ? "" : safeText(event.getErrorCode()),
                event == null ? "" : safeText(event.getErrorMessageShort()),
                summary("段落 atom", "PARENT/CHILD 和过滤诊断", List.of(
                        metric("PARENT", String.valueOf(parentCount), parentCount == 0 ? "warning" : "success"),
                        metric("CHILD", String.valueOf(childCount), childCount == 0 ? "warning" : "success"),
                        metric("保留诊断", String.valueOf(keptDiagnostics), "info"),
                        metric("过滤项", String.valueOf(filteredCount), filteredCount == 0 ? "success" : "warning")
                )),
                explanation(
                        "读取 OCR 生成的段落 atom，规划语义边界，生成 PARENT/CHILD chunk，并为保留或过滤的切片候选记录诊断。",
                        List.of("report_paragraph_atom 已存在", "OCR 阶段已产出可切片文本"),
                        List.of("report_chunk 保存最终落库 PARENT/CHILD", "report_chunk_diagnostic 保存保留和过滤原因"),
                        List.of("report_paragraph_atom", "report_document"),
                        List.of("report_chunk", "report_chunk_diagnostic", "report_chunk_tag_job"),
                        "VECTOR 阶段只消费最终落库且未向量化的 CHILD；过滤项不会进入 Milvus。",
                        event == null || safeText(event.getErrorCode()).isBlank() ? "" : "切片失败会导致向量阶段没有候选 CHILD，请检查段落 atom、边界规划和过滤诊断。"
                ),
                new ReportIngestChainObservationRespDTO.StageDetailsRespDTO(
                        List.of(),
                        List.of(),
                        parentTree,
                        filteredDiagnostics,
                        List.of()
                )
        );
    }

    private ReportIngestChainObservationRespDTO.StageRespDTO buildVectorStage(ReportIngestStageEvent event,
                                                                              List<ReportIngestChainObservationRespDTO.VectorCandidateRespDTO> candidates) {
        long storedCount = candidates.stream().filter(ReportIngestChainObservationRespDTO.VectorCandidateRespDTO::vectorStored).count();
        long pendingCount = candidates.size() - storedCount;
        return new ReportIngestChainObservationRespDTO.StageRespDTO(
                STAGE_VECTOR,
                "向量入库",
                statusOf(event, !candidates.isEmpty() && pendingCount == 0),
                event == null ? null : event.getDurationMs(),
                event == null ? "" : safeText(event.getModelName()),
                event == null ? null : event.getAttempt(),
                event == null ? "" : safeText(event.getErrorCode()),
                event == null ? "" : safeText(event.getErrorMessageShort()),
                summary("语义切片保留的 CHILD", "Milvus 向量文档和 vectorStored 状态", List.of(
                        metric("候选 CHILD", String.valueOf(candidates.size()), candidates.isEmpty() ? "warning" : "success"),
                        metric("已入库", String.valueOf(storedCount), storedCount == 0 ? "warning" : "success"),
                        metric("未入库", String.valueOf(pendingCount), pendingCount == 0 ? "success" : "warning")
                )),
                explanation(
                        "读取语义切片阶段保留的 CHILD，构造向量文档和 metadata，写入 Milvus 后回写 MySQL 的 vectorStored 状态。",
                        List.of("report_chunk 中存在 CHILD", "vectorStored=false 的 CHILD 是待入库候选"),
                        List.of("Milvus 可召回已写入的 CHILD", "report_chunk.vector_stored 反映 MySQL 侧入库状态"),
                        List.of("report_chunk CHILD", "report_document metadata"),
                        List.of("Milvus VectorStore", "report_chunk.vector_stored"),
                        "推荐检索只能召回已写入 Milvus 的 CHILD；未入库 CHILD 需要回到 CHUNK 阶段查看来源和状态。",
                        event == null || safeText(event.getErrorCode()).isBlank() ? "" : "向量失败通常影响推荐可检索性，请检查 VectorStore 配置、embedding 服务和未入库 CHILD。"
                ),
                new ReportIngestChainObservationRespDTO.StageDetailsRespDTO(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        candidates
                )
        );
    }

    private ReportIngestChainObservationRespDTO.StageSummaryRespDTO summary(String inputLabel,
                                                                            String outputLabel,
                                                                            List<ReportIngestChainObservationRespDTO.MetricRespDTO> metrics) {
        return new ReportIngestChainObservationRespDTO.StageSummaryRespDTO(inputLabel, outputLabel, metrics);
    }

    private ReportIngestChainObservationRespDTO.StageExplanationRespDTO explanation(String operationSummary,
                                                                                    List<String> beforeState,
                                                                                    List<String> afterState,
                                                                                    List<String> reads,
                                                                                    List<String> writes,
                                                                                    String impact,
                                                                                    String failureExplanation) {
        return new ReportIngestChainObservationRespDTO.StageExplanationRespDTO(operationSummary, beforeState, afterState, reads, writes, impact, failureExplanation);
    }

    private ReportIngestChainObservationRespDTO.MetricRespDTO metric(String label, String value, String tone) {
        return new ReportIngestChainObservationRespDTO.MetricRespDTO(label, value, tone);
    }

    private ReportIngestChainObservationRespDTO.StageDetailsRespDTO emptyDetails() {
        return new ReportIngestChainObservationRespDTO.StageDetailsRespDTO(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private ReportIngestChainObservationRespDTO.OcrPageDetailRespDTO ocrPageDetail(ReportOcrPage page) {
        return new ReportIngestChainObservationRespDTO.OcrPageDetailRespDTO(
                page.getPageNumber(),
                preview(page.getRawText()),
                preview(page.getCleanedText()),
                preview(page.getDiagnostics())
        );
    }

    private ReportIngestChainObservationRespDTO.ParagraphAtomDetailRespDTO paragraphAtomDetail(ReportParagraphAtom atom) {
        return new ReportIngestChainObservationRespDTO.ParagraphAtomDetailRespDTO(
                atom.getParagraphId(),
                atom.getPageNumber(),
                safeText(atom.getSectionPath()),
                atom.getTokenCount(),
                preview(atom.getParagraphText()),
                preview(atom.getDiagnostics())
        );
    }

    /**
     * @Description: 构建 PARENT-CHILD 树。
     * @Logic: 先按 chunkType 分离 PARENT 和 CHILD，再用 parentChunkUid 将 CHILD 挂到 PARENT 下；缺少 PARENT 的 CHILD 放入未关联分组。
     * @Param: chunks report_chunk 查询结果。
     * @Return: PARENT-CHILD 树节点列表。
     * @author: cx
     * @Date: 2026-05-30 00:00:00
     */
    private List<ReportIngestChainObservationRespDTO.ParentChunkRespDTO> buildParentTree(List<ReportChunk> chunks) {
        Map<String, ReportChunk> parentByUid = new LinkedHashMap<>();
        Map<String, List<ReportChunk>> childrenByParentUid = new LinkedHashMap<>();
        List<ReportChunk> orphanChildren = new ArrayList<>();
        for (ReportChunk chunk : safeList(chunks)) {
            if ("PARENT".equals(chunk.getChunkType())) {
                parentByUid.put(safeText(chunk.getChunkUid()), chunk);
                continue;
            }
            if ("CHILD".equals(chunk.getChunkType())) {
                String parentChunkUid = safeText(chunk.getParentChunkUid());
                if (parentChunkUid.isBlank()) {
                    orphanChildren.add(chunk);
                } else {
                    childrenByParentUid.computeIfAbsent(parentChunkUid, ignored -> new ArrayList<>()).add(chunk);
                }
            }
        }
        List<ReportIngestChainObservationRespDTO.ParentChunkRespDTO> parents = parentByUid.values().stream()
                .sorted(Comparator.comparing(ReportChunk::getChunkIndex, Comparator.nullsLast(Integer::compareTo)))
                .map(parent -> parentNode(parent, childrenByParentUid.getOrDefault(safeText(parent.getChunkUid()), List.of())))
                .collect(Collectors.toCollection(ArrayList::new));
        Set<String> knownParentUids = new HashSet<>(parentByUid.keySet());
        List<ReportChunk> childrenWithMissingParent = childrenByParentUid.entrySet().stream()
                .filter(entry -> !knownParentUids.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .collect(Collectors.toCollection(ArrayList::new));
        childrenWithMissingParent.addAll(orphanChildren);
        if (!childrenWithMissingParent.isEmpty()) {
            parents.add(parentNodeForOrphans(childrenWithMissingParent));
        }
        return parents;
    }

    private ReportIngestChainObservationRespDTO.ParentChunkRespDTO parentNode(ReportChunk parent, List<ReportChunk> children) {
        List<ReportIngestChainObservationRespDTO.ChildChunkRespDTO> childNodes = safeList(children).stream()
                .sorted(Comparator.comparing(ReportChunk::getChunkIndex, Comparator.nullsLast(Integer::compareTo)))
                .map(this::childNode)
                .toList();
        int vectorStoredCount = (int) childNodes.stream().filter(ReportIngestChainObservationRespDTO.ChildChunkRespDTO::vectorStored).count();
        return new ReportIngestChainObservationRespDTO.ParentChunkRespDTO(
                safeText(parent.getChunkUid()),
                parent.getChunkIndex(),
                safeText(parent.getSectionPath()),
                parent.getTokenCount(),
                pageRange(parent.getStartPageNumber(), parent.getEndPageNumber()),
                paragraphRange(parent.getStartParagraphId(), parent.getEndParagraphId()),
                childNodes.size(),
                vectorStoredCount,
                preview(parent.getChunkText()),
                childNodes
        );
    }

    private ReportIngestChainObservationRespDTO.ParentChunkRespDTO parentNodeForOrphans(List<ReportChunk> children) {
        List<ReportIngestChainObservationRespDTO.ChildChunkRespDTO> childNodes = safeList(children).stream()
                .sorted(Comparator.comparing(ReportChunk::getChunkIndex, Comparator.nullsLast(Integer::compareTo)))
                .map(this::childNode)
                .toList();
        int vectorStoredCount = (int) childNodes.stream().filter(ReportIngestChainObservationRespDTO.ChildChunkRespDTO::vectorStored).count();
        return new ReportIngestChainObservationRespDTO.ParentChunkRespDTO(
                "",
                null,
                "未关联 PARENT",
                null,
                "",
                "",
                childNodes.size(),
                vectorStoredCount,
                "这些 CHILD 缺少可关联的 PARENT chunk，请检查 parentChunkUid。",
                childNodes
        );
    }

    private ReportIngestChainObservationRespDTO.ChildChunkRespDTO childNode(ReportChunk child) {
        return new ReportIngestChainObservationRespDTO.ChildChunkRespDTO(
                safeText(child.getChunkUid()),
                safeText(child.getParentChunkUid()),
                child.getChunkIndex(),
                safeText(child.getSectionPath()),
                child.getTokenCount(),
                pageRange(child.getStartPageNumber(), child.getEndPageNumber()),
                paragraphRange(child.getStartParagraphId(), child.getEndParagraphId()),
                Boolean.TRUE.equals(child.getVectorStored()),
                preview(child.getChunkText())
        );
    }

    /**
     * @Description: 构建过滤诊断明细。
     * @Logic: 只展示被过滤或未落库的诊断项，并通过 chunkUid 判断其是否进入 report_chunk。
     * @Param: chunks 最终落库 chunk；diagnostics 切片诊断列表。
     * @Return: 过滤或未落库诊断明细。
     * @author: cx
     * @Date: 2026-05-30 00:00:00
     */
    private List<ReportIngestChainObservationRespDTO.ChunkDiagnosticRespDTO> buildFilteredDiagnostics(List<ReportChunk> chunks,
                                                                                                      List<ReportChunkDiagnostic> diagnostics) {
        Set<String> persistedChunkUids = safeList(chunks).stream()
                .map(ReportChunk::getChunkUid)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        return safeList(diagnostics).stream()
                .filter(diagnostic -> Boolean.FALSE.equals(diagnostic.getKept())
                        || safeText(diagnostic.getChunkUid()).isBlank()
                        || !persistedChunkUids.contains(diagnostic.getChunkUid()))
                .map(diagnostic -> diagnosticDetail(diagnostic, persistedChunkUids.contains(safeText(diagnostic.getChunkUid()))))
                .toList();
    }

    private ReportIngestChainObservationRespDTO.ChunkDiagnosticRespDTO diagnosticDetail(ReportChunkDiagnostic diagnostic,
                                                                                       boolean persisted) {
        return new ReportIngestChainObservationRespDTO.ChunkDiagnosticRespDTO(
                safeText(diagnostic.getChunkUid()),
                safeText(diagnostic.getParentChunkUid()),
                safeText(diagnostic.getChunkType()),
                diagnostic.getParentIndex(),
                diagnostic.getChunkIndexInParent(),
                Boolean.TRUE.equals(diagnostic.getKept()),
                persisted,
                safeText(diagnostic.getFilterReason()),
                preview(diagnostic.getDiagnostics()),
                pageRange(diagnostic.getStartPageNumber(), diagnostic.getEndPageNumber()),
                paragraphRange(diagnostic.getStartParagraphId(), diagnostic.getEndParagraphId()),
                diagnostic.getTokenCount(),
                preview(diagnostic.getChunkText())
        );
    }

    private List<ReportIngestChainObservationRespDTO.VectorCandidateRespDTO> buildVectorCandidates(List<ReportChunk> chunks) {
        Map<String, ReportChunk> parentByUid = safeList(chunks).stream()
                .filter(chunk -> "PARENT".equals(chunk.getChunkType()))
                .filter(chunk -> !safeText(chunk.getChunkUid()).isBlank())
                .collect(Collectors.toMap(ReportChunk::getChunkUid, chunk -> chunk, (left, right) -> left, LinkedHashMap::new));
        return safeList(chunks).stream()
                .filter(chunk -> "CHILD".equals(chunk.getChunkType()))
                .sorted(Comparator.comparing(ReportChunk::getChunkIndex, Comparator.nullsLast(Integer::compareTo)))
                .map(child -> vectorCandidate(child, parentByUid.get(safeText(child.getParentChunkUid()))))
                .toList();
    }

    private ReportIngestChainObservationRespDTO.VectorCandidateRespDTO vectorCandidate(ReportChunk child, ReportChunk parent) {
        boolean vectorStored = Boolean.TRUE.equals(child.getVectorStored());
        return new ReportIngestChainObservationRespDTO.VectorCandidateRespDTO(
                safeText(child.getChunkUid()),
                safeText(child.getParentChunkUid()),
                parent == null ? "" : safeText(parent.getSectionPath()),
                safeText(child.getSectionPath()),
                child.getTokenCount(),
                pageRange(child.getStartPageNumber(), child.getEndPageNumber()),
                vectorStored,
                vectorStored ? "已入库" : "未入库"
        );
    }

    private String statusOf(ReportIngestStageEvent event, boolean hasOutput) {
        if (event != null && event.getStatus() != null && !event.getStatus().isBlank()) {
            return event.getStatus();
        }
        return hasOutput ? "SUCCEEDED" : "NOT_STARTED";
    }

    private boolean hasChunkData(List<ReportChunk> chunks, List<ReportChunkDiagnostic> diagnostics) {
        return !safeList(chunks).isEmpty() || !safeList(diagnostics).isEmpty();
    }

    private boolean hasAnyVectorStored(List<ReportChunk> chunks) {
        return safeList(chunks).stream().anyMatch(chunk -> "CHILD".equals(chunk.getChunkType()) && Boolean.TRUE.equals(chunk.getVectorStored()));
    }

    private String resolveFailedStage(List<ReportIngestChainObservationRespDTO.StageRespDTO> stages) {
        return stages.stream()
                .filter(stage -> safeText(stage.status()).contains("FAILED") || !safeText(stage.errorCode()).isBlank())
                .map(ReportIngestChainObservationRespDTO.StageRespDTO::stage)
                .reduce((first, second) -> second)
                .orElse("");
    }

    private String resolveCurrentStage(List<ReportIngestChainObservationRespDTO.StageRespDTO> stages) {
        return stages.stream()
                .filter(stage -> !"SUCCEEDED".equals(stage.status()))
                .map(ReportIngestChainObservationRespDTO.StageRespDTO::stage)
                .findFirst()
                .orElse(STAGE_VECTOR);
    }

    private String resolveOverallStatus(List<ReportIngestChainObservationRespDTO.StageRespDTO> stages) {
        if (!resolveFailedStage(stages).isBlank()) {
            return "FAILED";
        }
        boolean allDone = stages.stream().allMatch(stage -> "SUCCEEDED".equals(stage.status()));
        return allDone ? "SUCCEEDED" : "PROCESSING";
    }

    private String pageRange(Integer start, Integer end) {
        return rangeText(start, end);
    }

    private String paragraphRange(Integer start, Integer end) {
        return rangeText(start, end);
    }

    private String rangeText(Integer start, Integer end) {
        if (start == null && end == null) {
            return "";
        }
        if (Objects.equals(start, end) || end == null) {
            return String.valueOf(start);
        }
        if (start == null) {
            return String.valueOf(end);
        }
        return start + "-" + end;
    }

    private String preview(String text) {
        String safeText = safeText(text).replaceAll("\\s+", " ").trim();
        if (safeText.length() <= PREVIEW_LENGTH) {
            return safeText;
        }
        return safeText.substring(0, PREVIEW_LENGTH) + "...";
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * @Description: 过滤并去重切片观测项。
     * @Logic: 观测接口只展示 CHILD 切片；按 chunkUid 稳定去重，缺失时使用 id 或内容范围兜底，避免父子切片或重复入库记录造成重复展示。
     * @Param: chunks 数据库查询出的切片列表。
     * @Return: 去重后的子切片列表。
     * @author: cx
     * @Date: 2026-05-29 00:00:00
     */
    private List<ReportChunk> deduplicateChildChunks(List<ReportChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        Map<String, ReportChunk> deduplicated = new LinkedHashMap<>();
        for (ReportChunk chunk : chunks) {
            if (chunk == null || !"CHILD".equals(chunk.getChunkType())) {
                continue;
            }
            deduplicated.putIfAbsent(chunkDedupKey(chunk), chunk);
        }
        return List.copyOf(deduplicated.values());
    }

    private String chunkDedupKey(ReportChunk chunk) {
        if (chunk.getChunkUid() != null && !chunk.getChunkUid().isBlank()) {
            return "chunkUid:" + chunk.getChunkUid();
        }
        if (chunk.getId() != null) {
            return "id:" + chunk.getId();
        }
        return "content:"
                + nullToEmpty(chunk.getSectionPath()) + "|"
                + nullToEmpty(chunk.getStartParagraphId()) + "|"
                + nullToEmpty(chunk.getEndParagraphId()) + "|"
                + normalizeForCompare(chunk.getChunkText());
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * @Description: 根据切片段落范围拼接切片前原文。
     * @Logic: 当起止段落存在时按段落ID顺序拼接；否则回退为空字符串避免空指针。
     * @Param: chunk 切片对象；paragraphMap 段落映射。
     * @Return: 切片前原文文本。
     * @author: cx
     * @Date: 2026-05-20 23:40:00
     */
    private String resolveSourceParagraphText(ReportChunk chunk, Map<Integer, ReportParagraphAtom> paragraphMap) {
        Integer startId = chunk.getStartParagraphId();
        Integer endId = chunk.getEndParagraphId();
        if (startId == null || endId == null || endId < startId) {
            return "";
        }
        return java.util.stream.IntStream.rangeClosed(startId, endId)
                .mapToObj(paragraphMap::get)
                .filter(Objects::nonNull)
                .map(ReportParagraphAtom::getParagraphText)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private String normalizeForCompare(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", "");
    }

    public record ReportQualityData(
            List<ReportOcrPage> ocrPages,
            List<ReportParagraphAtom> paragraphAtoms,
            List<ReportChunk> chunks,
            List<ReportChunkDiagnostic> chunkDiagnostics
    ) {
        public ReportQualityData {
            ocrPages = ocrPages == null ? List.of() : List.copyOf(ocrPages);
            paragraphAtoms = paragraphAtoms == null ? List.of() : List.copyOf(paragraphAtoms);
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            chunkDiagnostics = chunkDiagnostics == null ? List.of() : List.copyOf(chunkDiagnostics);
        }
    }
}
