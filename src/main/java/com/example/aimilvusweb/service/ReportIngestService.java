package com.example.aimilvusweb.service;

import com.alibaba.fastjson2.JSON;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ReportChunkSlice;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ReportSemanticChunks;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ParagraphAtom;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.dto.ReportUploadRespDTO;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportChunkDiagnostic;
import com.example.aimilvusweb.entity.ReportDocument;
import com.example.aimilvusweb.entity.ReportOcrPage;
import com.example.aimilvusweb.entity.ReportParagraphAtom;
import com.example.aimilvusweb.repository.ReportChunkDiagnosticMapper;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import com.example.aimilvusweb.repository.ReportOcrPageMapper;
import com.example.aimilvusweb.repository.ReportParagraphAtomMapper;
import com.example.aimilvusweb.service.ReportOcrParseService.ReportOcrParseResult;
import com.example.aimilvusweb.service.ReportOcrParseService.OcrPageResult;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
/**
 * @Description: ReportIngestService类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class ReportIngestService {
    private static final int EMBEDDING_BATCH_SIZE = 10;
    /**
     * @Description: 章节路径关键词黑名单，命中后该切片会被判定为低价值内容并过滤。
     * @Logic: 用于识别免责声明、分析师声明、联系方式等非投资研究正文内容，避免进入检索与向量库。
     * @Param: 无。
     * @Return: 无（仅常量定义）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static final Set<String> EXCLUDED_SECTION_KEYWORDS = Set.of(
            "免责声明", "免责条款", "法律声明", "分析师承诺", "评级说明", "投资评级说明", "风险披露",
            "分析师声明", "研究所联系方式", "联系方式", "券商简介", "机构介绍", "中邮证券研究所"
    );
    /**
     * @Description: 语义段类型黑名单，命中后切片不会进入最终有效 chunk 集合。
     * @Logic: 结合 LLM 返回的 segmentType 做快速过滤，拦截版式噪声和非业务正文。
     * @Param: 无。
     * @Return: 无（仅常量定义）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static final Set<String> EXCLUDED_SEGMENT_TYPES = Set.of(
            "DISCLAIMER", "ANALYST_DECLARATION", "BROKER_PROFILE", "CONTACT_INFO", "LAYOUT_NOISE"
    );
    /**
     * @Description: 财务表格主题关键词集合，用于“短文本但高价值财务内容”的豁免判断。
     * @Logic: 当切片命中财务关键词时，即使文本较短或噪声特征偏高，也尽量保留进入检索链路。
     * @Param: 无。
     * @Return: 无（仅常量定义）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static final Set<String> FINANCIAL_TABLE_KEYWORDS = Set.of(
            "盈利预测", "财务指标", "财务报表", "主要财务比率", "利润表", "资产负债表", "现金流量表",
            "营业收入", "归母净利润", "每股收益", "EPS", "P/E", "P/B", "市盈率", "市净率"
    );

    private final ReportDocumentMapper reportDocumentMapper;
    private final ReportChunkMapper reportChunkMapper;
    private final ReportOcrPageMapper reportOcrPageMapper;
    private final ReportParagraphAtomMapper reportParagraphAtomMapper;
    private final ReportChunkDiagnosticMapper reportChunkDiagnosticMapper;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ReportOcrParseService reportOcrParseService;
    private final ReportSemanticChunkService reportSemanticChunkService;
    private final ReportIngestFailureService reportIngestFailureService;
    private final ReportQualityProperties reportQualityProperties;
    /** chunk 标签抽取 job 服务提供器，用于 chunk 落库后异步创建结构化标签任务。 */
    private final ObjectProvider<ReportChunkTagJobService> reportChunkTagJobServiceProvider;
    /** 向量 metadata 服务提供器，用于构建带结构化标签摘要的 Milvus Document。 */
    private final ObjectProvider<ReportVectorMetadataSyncJobService> metadataSyncJobServiceProvider;

    /**
     * @Description: 初始化ReportIngestService依赖与运行所需组件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    @Autowired
    public ReportIngestService(ReportDocumentMapper reportDocumentMapper,
                               ReportChunkMapper reportChunkMapper,
                               ReportOcrPageMapper reportOcrPageMapper,
                               ReportParagraphAtomMapper reportParagraphAtomMapper,
                               ReportChunkDiagnosticMapper reportChunkDiagnosticMapper,
                               ObjectProvider<VectorStore> vectorStoreProvider,
                               ReportOcrParseService reportOcrParseService,
                               ReportSemanticChunkService reportSemanticChunkService,
                               ReportIngestFailureService reportIngestFailureService,
                               ReportQualityProperties reportQualityProperties,
                               ObjectProvider<ReportChunkTagJobService> reportChunkTagJobServiceProvider,
                               ObjectProvider<ReportVectorMetadataSyncJobService> metadataSyncJobServiceProvider) {
        this.reportDocumentMapper = reportDocumentMapper;
        this.reportChunkMapper = reportChunkMapper;
        this.reportOcrPageMapper = reportOcrPageMapper;
        this.reportParagraphAtomMapper = reportParagraphAtomMapper;
        this.reportChunkDiagnosticMapper = reportChunkDiagnosticMapper;
        this.vectorStoreProvider = vectorStoreProvider;
        this.reportOcrParseService = reportOcrParseService;
        this.reportSemanticChunkService = reportSemanticChunkService;
        this.reportIngestFailureService = reportIngestFailureService;
        this.reportQualityProperties = reportQualityProperties;
        this.reportChunkTagJobServiceProvider = reportChunkTagJobServiceProvider;
        this.metadataSyncJobServiceProvider = metadataSyncJobServiceProvider;
    }

    /**
     * @Description: 兼容旧测试的导入服务构造器。
     * @Logic: 未提供标签 job 和 metadata sync 服务时，导入链路退化为原有 OCR/切片/向量流程。
     * @Param: reportDocumentMapper 主档 Mapper；reportChunkMapper 切片 Mapper；reportOcrPageMapper OCR 页 Mapper；reportParagraphAtomMapper 段落 Mapper；reportChunkDiagnosticMapper 诊断 Mapper；vectorStoreProvider 向量库提供器；reportOcrParseService OCR 服务；reportSemanticChunkService 切片服务；reportIngestFailureService 失败记录服务；reportQualityProperties 质量配置。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public ReportIngestService(ReportDocumentMapper reportDocumentMapper,
                               ReportChunkMapper reportChunkMapper,
                               ReportOcrPageMapper reportOcrPageMapper,
                               ReportParagraphAtomMapper reportParagraphAtomMapper,
                               ReportChunkDiagnosticMapper reportChunkDiagnosticMapper,
                               ObjectProvider<VectorStore> vectorStoreProvider,
                               ReportOcrParseService reportOcrParseService,
                               ReportSemanticChunkService reportSemanticChunkService,
                               ReportIngestFailureService reportIngestFailureService,
                               ReportQualityProperties reportQualityProperties) {
        this(reportDocumentMapper, reportChunkMapper, reportOcrPageMapper, reportParagraphAtomMapper,
                reportChunkDiagnosticMapper, vectorStoreProvider, reportOcrParseService, reportSemanticChunkService,
                reportIngestFailureService, reportQualityProperties, null, null);
    }

    /**
     * @Description: 执行ingest相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    @Transactional
    public ReportUploadRespDTO ingest(MultipartFile file, String title, String source, String institution, LocalDate publishDate) {
        String stage = "VALIDATION";
        try {
            // 先确保向量存储可用，避免前置处理成功后才在落向量阶段失败导致补偿复杂化。
            VectorStore vectorStore = requireVectorStore();
            validateFile(file);
            stage = "OCR_AND_CHUNK";
            // OCR 与语义切片绑定在同一预处理步骤，保证后续落库输入的一致性。
            IngestPreparation preparation = parseAndChunk(file);
            stage = "MYSQL";
            ReportDocument report = persistReportDocument(file, title, source, institution, publishDate);
            persistOcrQualityData(report, preparation.ocrResult());
            List<ReportChunk> persistedChildChunks = persistChunks(report, preparation.chunks());
            stage = "MILVUS";
            // 向量写入采用批量策略，降低单次请求体积并便于定位失败批次。
            addVectorDocumentsInBatches(vectorStore, buildVectorDocuments(report, persistedChildChunks));
            return buildUploadResp(null, report, persistedChildChunks.size());
        } catch (RuntimeException e) {
            // 记录当前失败阶段用于运维排障与后续重试策略判定。
            reportIngestFailureService.recordFailure(file, title, source, institution, stage, e);
            throw e;
        }
    }

    /**
     * @Description: 异步 OCR 阶段执行入口，负责主档入库与 OCR/段落数据落库。
     * @Logic: 校验文件后先创建 report_document，再写入页级 OCR 与 paragraph atom；已绑定 reportId 时直接返回以保障幂等。
     * @Param: file 上传文件；title/source/institution/publishDate 报告元信息；existingReportId 已存在报告ID。
     * @Return: 报告ID。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    @Transactional
    public Long ingestOcrStage(MultipartFile file,
                               String title,
                               String source,
                               String institution,
                               LocalDate publishDate,
                               Long existingReportId) {
        if (existingReportId != null) {
            return existingReportId;
        }
        validateFile(file);
        ReportOcrParseResult ocrResult = reportOcrParseService.parseDetailed(file);
        ReportDocument report = persistReportDocument(file, title, source, institution, publishDate);
        persistOcrQualityData(report, ocrResult);
        return report.getId();
    }

    /**
     * @Description: 异步 Chunk 阶段执行入口，根据已落库段落生成并持久化语义切片。
     * @Logic: 已存在切片时直接返回避免重复；否则读取 paragraph atom，调用切片服务生成并写入 chunk 与诊断表。
     * @Param: reportId 报告ID。
     * @Return: 子切片数量。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    @Transactional
    public int ingestChunkStage(Long reportId) {
        // 先查当前报告已有切片，作为幂等保护（避免调度重入导致重复写库）。
        List<ReportChunk> existingChunks = reportChunkMapper.selectByReportId(reportId);
        // 只统计 CHILD，因为检索与向量化主要消费 CHILD；PARENT 仅用于结构回溯。
        long existingChildCount = existingChunks.stream().filter(chunk -> "CHILD".equals(chunk.getChunkType())).count();
        // 发现已有子切片时直接返回，避免重复切片造成数据膨胀。
        if (existingChildCount > 0) {
            return (int) existingChildCount;
        }
        // 读取 OCR 阶段已落库的 paragraph atoms，作为切片输入语料。
        List<ParagraphAtom> atoms = reportParagraphAtomMapper.selectByReportId(reportId).stream()
                // 按 paragraphId 排序，保证切片时段落时序稳定、结果可复现。
                .sorted(Comparator.comparing(ReportParagraphAtom::getParagraphId))
                // 数据归一化：把数据库 nullable 字段兜底，避免后续切片 NPE。
                .map(atom -> new ParagraphAtom(
                        atom.getParagraphId(),
                        atom.getPageNumber() == null ? 0 : atom.getPageNumber(),
                        atom.getSectionPath() == null ? "正文" : atom.getSectionPath(),
                        atom.getParagraphText(),
                        atom.getTokenCount() == null ? 0 : atom.getTokenCount(),
                        atom.getDiagnostics() == null ? "" : atom.getDiagnostics()))
                .toList();
        // 调用语义切片核心：输出 parent/child 草案（含段落范围、页码范围和 token 统计）。
        ReportSemanticChunks chunks = reportSemanticChunkService.chunk(atoms);
        // 空切片直接失败，避免“任务成功但无有效检索数据”的假阳性状态。
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("No valid chunks generated from paragraph atoms");
        }
        // 再次校验 report 主档存在，防止并发删除或数据不一致。
        ReportDocument report = reportDocumentMapper.selectById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("Report not found: " + reportId);
        }
        // 持久化切片与诊断数据，返回 CHILD 数量供阶段事件 outputSize 展示。
        return persistChunks(report, chunks).size();
    }

    /**
     * @Description: 异步向量阶段执行入口，将已落库子切片写入向量库并回写向量状态。
     * @Logic: 只处理未向量化 CHILD 切片，分批写入 Milvus，成功后逐条回写 vectorStored=true。
     * @Param: reportId 报告ID。
     * @Return: 本次向量化切片数量。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    @Transactional
    public int ingestVectorStage(Long reportId) {
        VectorStore vectorStore = requireVectorStore();
        ReportDocument report = reportDocumentMapper.selectById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("Report not found: " + reportId);
        }
        List<ReportChunk> allChunks = reportChunkMapper.selectByReportId(reportId);
        List<ReportChunk> pendingChildren = allChunks.stream()
                .filter(chunk -> "CHILD".equals(chunk.getChunkType()))
                .filter(chunk -> chunk.getVectorStored() == null || !chunk.getVectorStored())
                .toList();
        // 只处理未入向量切片，保障向量阶段可重复执行且结果幂等。
        if (pendingChildren.isEmpty()) {
            return 0;
        }
        addVectorDocumentsInBatches(vectorStore, buildVectorDocuments(report, pendingChildren));
        for (ReportChunk chunk : pendingChildren) {
            reportChunkMapper.updateVectorStoredByChunkUid(chunk.getChunkUid(), true);
        }
        return pendingChildren.size();
    }

    /**
     * @Description: 执行requireVectorStore相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private VectorStore requireVectorStore() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new IllegalStateException("VectorStore is not configured. Set spring.ai.vectorstore.type=milvus and Milvus properties.");
        }
        return vectorStore;
    }

    /**
     * @Description: 校验输入参数与业务约束。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Report file is required");
        }
    }

    /**
     * @Description: 解析输入内容并输出结构化结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private IngestPreparation parseAndChunk(MultipartFile file) {
        ReportOcrParseResult ocrResult = reportOcrParseService.parseDetailed(file);
        ReportSemanticChunks chunks = reportSemanticChunkService.chunk(ocrResult.atoms());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("No valid chunks generated from OCR text");
        }
        return new IngestPreparation(ocrResult, chunks);
    }

    /**
     * @Description: 执行persistReportDocument相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private ReportDocument persistReportDocument(MultipartFile file, String title, String source, String institution, LocalDate publishDate) {
        ReportDocument report = new ReportDocument();
        report.setTitle((title == null || title.isBlank()) ? file.getOriginalFilename() : title.trim());
        report.setSource((source == null || source.isBlank()) ? "uploaded" : source.trim());
        report.setInstitution(institution == null ? null : institution.trim());
        report.setPublishDate(publishDate);
        report.setCreatedAt(Instant.now());
        reportDocumentMapper.insert(report);
        return report;
    }

    /**
     * @Description: 执行persistOcrQualityData相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private void persistOcrQualityData(ReportDocument report, ReportOcrParseResult ocrResult) {
        for (OcrPageResult page : ocrResult.pages()) {
            ReportOcrPage ocrPage = new ReportOcrPage();
            ocrPage.setReportId(report.getId());
            ocrPage.setPageNumber(page.pageNumber());
            ocrPage.setRawText(page.rawText());
            ocrPage.setCleanedText(page.cleanedText());
            ocrPage.setDiagnostics(page.diagnostics());
            ocrPage.setCreatedAt(Instant.now());
            reportOcrPageMapper.insert(ocrPage);
        }
        for (ParagraphAtom atom : ocrResult.atoms()) {
            ReportParagraphAtom paragraphAtom = new ReportParagraphAtom();
            paragraphAtom.setReportId(report.getId());
            paragraphAtom.setParagraphId(atom.paragraphId());
            paragraphAtom.setPageNumber(atom.pageNumber());
            paragraphAtom.setSectionPath(atom.sectionPath());
            paragraphAtom.setParagraphText(atom.text());
            paragraphAtom.setTokenCount(atom.tokenCount());
            paragraphAtom.setDiagnostics(atom.diagnostics());
            paragraphAtom.setCreatedAt(Instant.now());
            reportParagraphAtomMapper.insert(paragraphAtom);
        }
    }

    /**
     * @Description: 执行persistChunks相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private List<ReportChunk> persistChunks(ReportDocument report, ReportSemanticChunks chunks) {
        List<ReportChunkSlice> filteredParents = chunks.parents().stream()
                .filter(this::shouldKeepSlice)
                .toList();
        Map<Integer, String> parentUidByIndex = filteredParents.stream()
                .collect(Collectors.toMap(ReportChunkSlice::parentIndex, ignored -> newChunkUid()));

        for (ReportChunkSlice parentSlice : chunks.parents()) {
            String filterReason = resolveFilterReason(parentSlice);
            String parentChunkUid = parentUidByIndex.get(parentSlice.parentIndex());
            if (filterReason == null) {
                ReportChunk parentChunk = buildReportChunk(report, parentSlice, parentChunkUid, null, false, null);
                reportChunkMapper.insert(parentChunk);
                persistChunkDiagnostic(report, parentSlice, parentChunkUid, null, true, null);
            } else {
                persistChunkDiagnostic(report, parentSlice, null, null, false, filterReason);
            }
        }

        List<ReportChunk> persistedChildChunks = new ArrayList<>();
        List<ReportChunkSlice> filteredChildren = chunks.children().stream()
                .filter(this::shouldKeepSlice)
                .filter(slice -> parentUidByIndex.containsKey(slice.parentIndex()))
                .toList();
        for (int i = 0; i < filteredChildren.size(); i++) {
            ReportChunkSlice childSlice = filteredChildren.get(i);
            String parentChunkUid = parentUidByIndex.get(childSlice.parentIndex());
            ReportChunk childChunk = buildReportChunk(report, childSlice, newChunkUid(), parentChunkUid, true, null);
            childChunk.setChunkIndex(i);
            reportChunkMapper.insert(childChunk);
            persistChunkDiagnostic(report, childSlice, childChunk.getChunkUid(), parentChunkUid, true, null);
            enqueueChunkTagJob(childChunk);
            persistedChildChunks.add(childChunk);
        }
        for (ReportChunkSlice childSlice : chunks.children()) {
            String filterReason = resolveFilterReason(childSlice);
            if (filterReason != null || !parentUidByIndex.containsKey(childSlice.parentIndex())) {
                String reason = filterReason == null ? "PARENT_FILTERED" : filterReason;
                persistChunkDiagnostic(report, childSlice, null, parentUidByIndex.get(childSlice.parentIndex()), false, reason);
            }
        }
        return persistedChildChunks;
    }

    /**
     * @Description: 构建目标对象或请求数据。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private ReportChunk buildReportChunk(ReportDocument report,
                                         ReportChunkSlice slice,
                                         String chunkUid,
                                         String parentChunkUid,
                                         boolean vectorStored,
                                         String filterReason) {
        ReportChunk chunk = new ReportChunk();
        chunk.setReportId(report.getId());
        chunk.setChunkIndex(slice.chunkType().equals("PARENT") ? slice.parentIndex() : slice.chunkIndexInParent());
        chunk.setChunkUid(chunkUid);
        chunk.setParentChunkUid(parentChunkUid);
        chunk.setChunkType(slice.chunkType());
        chunk.setSectionPath(slice.sectionPath());
        chunk.setChunkText(slice.text());
        chunk.setTokenCount(slice.tokenCount());
        chunk.setPageNumber(slice.startPageNumber());
        chunk.setStartParagraphId(slice.startParagraphId());
        chunk.setEndParagraphId(slice.endParagraphId());
        chunk.setStartPageNumber(slice.startPageNumber());
        chunk.setEndPageNumber(slice.endPageNumber());
        chunk.setFilterReason(filterReason);
        chunk.setDiagnostics(buildChunkDiagnostics(slice, filterReason));
        chunk.setVectorStored(vectorStored);
        chunk.setCreatedAt(Instant.now());
        return chunk;
    }

    /**
     * @Description: 执行persistChunkDiagnostic相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private void persistChunkDiagnostic(ReportDocument report,
                                        ReportChunkSlice slice,
                                        String chunkUid,
                                        String parentChunkUid,
                                        boolean kept,
                                        String filterReason) {
        ReportChunkDiagnostic diagnostic = new ReportChunkDiagnostic();
        diagnostic.setReportId(report.getId());
        diagnostic.setChunkUid(chunkUid);
        diagnostic.setParentChunkUid(parentChunkUid);
        diagnostic.setParentIndex(slice.parentIndex());
        diagnostic.setChunkIndexInParent(slice.chunkIndexInParent());
        diagnostic.setChunkType(slice.chunkType());
        diagnostic.setSectionPath(slice.sectionPath());
        diagnostic.setTokenCount(slice.tokenCount());
        diagnostic.setStartParagraphId(slice.startParagraphId());
        diagnostic.setEndParagraphId(slice.endParagraphId());
        diagnostic.setStartPageNumber(slice.startPageNumber());
        diagnostic.setEndPageNumber(slice.endPageNumber());
        diagnostic.setKept(kept);
        diagnostic.setFilterReason(filterReason);
        diagnostic.setDiagnostics(buildChunkDiagnostics(slice, filterReason));
        diagnostic.setChunkText(slice.text());
        diagnostic.setCreatedAt(Instant.now());
        reportChunkDiagnosticMapper.insert(diagnostic);
    }

    /**
     * @Description: 执行newChunkUid相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String newChunkUid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * @Description: 构建目标对象或请求数据。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private List<Document> buildVectorDocuments(ReportDocument report, List<ReportChunk> chunks) {
        ReportVectorMetadataSyncJobService metadataService = metadataSyncJobServiceProvider == null ? null : metadataSyncJobServiceProvider.getIfAvailable();
        List<Document> vectorDocuments = new ArrayList<>();
        for (ReportChunk chunk : chunks) {
            if (metadataService != null) {
                vectorDocuments.add(metadataService.buildVectorDocument(report, chunk));
                continue;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("reportId", report.getId());
            metadata.put("chunkId", chunk.getId());
            metadata.put("chunkUid", chunk.getChunkUid());
            metadata.put("parentChunkUid", chunk.getParentChunkUid() == null ? "" : chunk.getParentChunkUid());
            metadata.put("chunkType", chunk.getChunkType());
            metadata.put("chunkIndex", chunk.getChunkIndex());
            metadata.put("sectionPath", chunk.getSectionPath() == null ? "" : chunk.getSectionPath());
            metadata.put("tokenCount", chunk.getTokenCount() == null ? 0 : chunk.getTokenCount());
            metadata.put("startParagraphId", chunk.getStartParagraphId() == null ? 0 : chunk.getStartParagraphId());
            metadata.put("endParagraphId", chunk.getEndParagraphId() == null ? 0 : chunk.getEndParagraphId());
            metadata.put("startPageNumber", chunk.getStartPageNumber() == null ? 0 : chunk.getStartPageNumber());
            metadata.put("endPageNumber", chunk.getEndPageNumber() == null ? 0 : chunk.getEndPageNumber());
            metadata.put("title", report.getTitle());
            metadata.put("source", report.getSource());
            metadata.put("institution", report.getInstitution() == null ? "" : report.getInstitution());
            metadata.put("publishDate", report.getPublishDate() == null ? "" : report.getPublishDate().toString());
            metadata.put("reportThemeCode", "");
            metadata.put("reportThemeCodes", List.of());
            metadata.put("themeCode", "");
            metadata.put("industryCode", "");
            metadata.put("companyName", "");
            metadata.put("ticker", "");
            metadata.put("themeCodes", List.of());
            metadata.put("industryCodes", List.of());
            metadata.put("companyNames", List.of());
            metadata.put("tickers", List.of());
            vectorDocuments.add(new Document(chunk.getChunkUid(), chunk.getChunkText(), metadata));
        }
        return vectorDocuments;
    }

    /**
     * @Description: 为新落库的子切片创建结构化标签抽取 job。
     * @Logic: 标签 job 服务存在时异步排队；服务不可用时跳过，避免影响 OCR/切片/向量主链路。
     * @Param: chunk 新落库子切片。
     * @Return: 无（仅创建标签抽取 job 副作用）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private void enqueueChunkTagJob(ReportChunk chunk) {
        // 通过 ObjectProvider 降级，避免测试或局部环境未装配标签服务时阻断导入主流程。
        ReportChunkTagJobService tagJobService = reportChunkTagJobServiceProvider == null ? null : reportChunkTagJobServiceProvider.getIfAvailable();
        if (tagJobService != null) {
            tagJobService.enqueue(chunk);
        }
    }

    /**
     * @Description: 向目标集合追加处理结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private void addVectorDocumentsInBatches(VectorStore vectorStore, List<Document> documents) {
        for (int start = 0; start < documents.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(documents.size(), start + EMBEDDING_BATCH_SIZE);
            vectorStore.add(documents.subList(start, end));
        }
    }

    /**
     * @Description: 执行shouldKeepSlice相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private boolean shouldKeepSlice(ReportChunkSlice slice) {
        return resolveFilterReason(slice) == null;
    }

    /**
     * @Description: 根据上下文解析并确定最终值。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String resolveFilterReason(ReportChunkSlice slice) {
        String segmentType = normalizedSegmentType(slice);
        if (EXCLUDED_SEGMENT_TYPES.contains(segmentType)) {
            return "EXCLUDED_SEGMENT_TYPE:" + segmentType;
        }
        String sectionPath = slice.sectionPath() == null ? "" : slice.sectionPath().trim();
        if (!sectionPath.isBlank()) {
            for (String keyword : EXCLUDED_SECTION_KEYWORDS) {
                if (sectionPath.contains(keyword)) {
                    return "EXCLUDED_SECTION:" + keyword;
                }
            }
        }
        return resolveLowSemanticReason(slice);
    }

    /**
     * @Description: 根据上下文解析并确定最终值。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String resolveLowSemanticReason(ReportChunkSlice slice) {
        if (slice.tokenCount() < reportQualityProperties.getChunk().getMinSliceTokenCount()) {
            return "LOW_TOKEN_COUNT:" + slice.tokenCount();
        }
        String text = slice.text() == null ? "" : slice.text();
        if (isFinancialTableCandidate(slice)) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", "");
        if (normalized.length() < 60) {
            return "SHORT_TEXT:" + normalized.length();
        }
        int han = 0;
        int digits = 0;
        int symbols = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                han++;
            } else if (Character.isDigit(ch)) {
                digits++;
            } else if (!Character.isLetter(ch)) {
                symbols++;
            }
        }
        double hanRatio = han / (double) normalized.length();
        double noiseRatio = (digits + symbols) / (double) normalized.length();
        if (hanRatio < 0.20D) {
            return "LOW_HAN_RATIO:" + hanRatio;
        }
        if (noiseRatio > 0.65D) {
            return "HIGH_NOISE_RATIO:" + noiseRatio;
        }
        return null;
    }

    /**
     * @Description: 构建目标对象或请求数据。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String buildChunkDiagnostics(ReportChunkSlice slice, String filterReason) {
        Map<String, Object> diagnostics = new HashMap<>();
        diagnostics.put("startParagraphId", slice.startParagraphId());
        diagnostics.put("endParagraphId", slice.endParagraphId());
        diagnostics.put("startPageNumber", slice.startPageNumber());
        diagnostics.put("endPageNumber", slice.endPageNumber());
        diagnostics.put("segmentType", normalizedSegmentType(slice));
        diagnostics.put("financialTableCandidate", isFinancialTableCandidate(slice));
        diagnostics.put("filterReason", filterReason == null ? "" : filterReason);
        return JSON.toJSONString(diagnostics);
    }

    /**
     * @Description: 对输入数据进行规范化处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String normalizedSegmentType(ReportChunkSlice slice) {
        String segmentType = slice.segmentType();
        if (segmentType == null || segmentType.isBlank()) {
            return "OTHER";
        }
        return segmentType.trim().toUpperCase();
    }

    /**
     * @Description: 判断是否满足FinancialTableCandidate条件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private boolean isFinancialTableCandidate(ReportChunkSlice slice) {
        String segmentType = normalizedSegmentType(slice);
        if ("FINANCIAL_TABLE".equals(segmentType) || "FINANCIAL_FORECAST".equals(segmentType)) {
            return true;
        }
        String haystack = ((slice.sectionPath() == null ? "" : slice.sectionPath()) + "\n" + (slice.text() == null ? "" : slice.text())).toUpperCase();
        for (String keyword : FINANCIAL_TABLE_KEYWORDS) {
            if (haystack.contains(keyword.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @Description: 构建目标对象或请求数据。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private ReportUploadRespDTO buildUploadResp(String jobId, ReportDocument report, int chunkCount) {
        return new ReportUploadRespDTO(jobId, report.getId(), report.getTitle(), chunkCount, "Upload and ingest completed");
    }

    /**
     * @Description: 导入预处理结果，封装 OCR 结果与语义切片结果。
     * @Logic: 避免主流程中分散传递多个中间变量，保证 OCR 与切片上下文的一致性。
     * @Param: ocrResult OCR 解析结果；chunks 语义切片结果。
     * @Return: 无（仅数据载体）。
     * @author: cx
     * @Date: 2026-05-21 23:20:00
     */
    private record IngestPreparation(ReportOcrParseResult ocrResult, ReportSemanticChunks chunks) {
    }
}
