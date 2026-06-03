package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.common.util.SemanticChunkUtils.ReportChunkSlice;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ReportSemanticChunks;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ParagraphAtom;
import com.example.aimilvusweb.common.util.TextEncodingRepairUtils;
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
import com.example.aimilvusweb.service.chunk.ReportSemanticChunkService;
import com.example.aimilvusweb.service.ingest.ReportOcrParseService.ReportOcrParseResult;
import com.example.aimilvusweb.service.ingest.ReportOcrParseService.OcrPageResult;
import com.example.aimilvusweb.service.ingest.ReportChunkFilterPolicy;
import com.example.aimilvusweb.service.ingest.ReportVectorBatchWriter;
import com.example.aimilvusweb.service.ingest.ReportVectorDocumentBuilder;
import com.example.aimilvusweb.service.retrieval.ReportHybridVectorStore;
import com.example.aimilvusweb.service.retrieval.SpringVectorStoreReportHybridVectorStore;
import com.example.aimilvusweb.service.tag.ReportChunkTagJobService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * @Description: 研报导入服务，编排同步导入和异步 OCR/切片/向量阶段。
 * @Logic: 将上传文件解析为 OCR 页、段落 atom、PARENT/CHILD chunk、作者元数据和 Milvus hybrid 向量文档。
 * @Param: 无。
 * @Return: 无（由各业务方法返回导入结果或阶段计数）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
@Service
public class ReportIngestService {
    /** 研报主档 Mapper，用于创建和查询 report_document。 */
    private final ReportDocumentMapper reportDocumentMapper;
    /** 研报切片 Mapper，用于创建和查询 PARENT/CHILD chunk。 */
    private final ReportChunkMapper reportChunkMapper;
    /** OCR 页 Mapper，用于保存页级 OCR 结果。 */
    private final ReportOcrPageMapper reportOcrPageMapper;
    /** 段落 atom Mapper，用于保存 OCR 后的段落级切片输入。 */
    private final ReportParagraphAtomMapper reportParagraphAtomMapper;
    /** chunk 诊断 Mapper，用于保存切片过滤和质量诊断。 */
    private final ReportChunkDiagnosticMapper reportChunkDiagnosticMapper;
    /** hybrid 向量存储，生产链路写入 Milvus BM25+dense collection。 */
    private final ReportHybridVectorStore hybridVectorStore;
    /** OCR 解析服务，用于把上传 PDF 转为页文本和段落 atom。 */
    private final ReportOcrParseService reportOcrParseService;
    /** 语义切片服务，用于把段落 atom 组织为 PARENT/CHILD chunk。 */
    private final ReportSemanticChunkService reportSemanticChunkService;
    /** 导入失败服务，用于记录同步导入失败阶段和错误信息。 */
    private final ReportIngestFailureService reportIngestFailureService;
    /** chunk 标签抽取 job 服务提供器，用于 chunk 落库后异步创建结构化标签任务。 */
    private final ObjectProvider<ReportChunkTagJobService> reportChunkTagJobServiceProvider;
    /** chunk 过滤策略，用于判定切片是否保留并生成诊断。 */
    private final ReportChunkFilterPolicy chunkFilterPolicy;
    /** 向量文档构建器，用于生成 Milvus Document metadata。 */
    private final ReportVectorDocumentBuilder vectorDocumentBuilder;
    /** 向量批量写入器，用于控制单批写入规模。 */
    private final ReportVectorBatchWriter vectorBatchWriter;
    /** 研报作者服务，用于保存导入作者并为 Milvus metadata 提供事实来源。 */
    private final ReportAuthorService reportAuthorService;

    /**
     * @Description: 初始化研报导入服务依赖。
     * @Logic: 保存 OCR、切片、MySQL Mapper、hybrid 向量存储、标签 job、向量文档构建和作者服务依赖。
     * @Param: reportDocumentMapper 主档 Mapper；reportChunkMapper chunk Mapper；reportOcrPageMapper OCR 页 Mapper；reportParagraphAtomMapper 段落 Mapper；reportChunkDiagnosticMapper 诊断 Mapper；hybridVectorStore hybrid 向量存储；reportOcrParseService OCR 服务；reportSemanticChunkService 切片服务；reportIngestFailureService 失败记录服务；reportQualityProperties 质量配置；reportChunkTagJobServiceProvider 标签 job 服务提供器；metadataSyncJobServiceProvider metadata 同步服务提供器；chunkFilterPolicy chunk 过滤策略；vectorDocumentBuilder 向量文档构建器；vectorBatchWriter 向量批写入器；reportAuthorService 作者服务。
     * @Return: 无（仅初始化服务依赖）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    @Autowired
    public ReportIngestService(ReportDocumentMapper reportDocumentMapper,
                               ReportChunkMapper reportChunkMapper,
                               ReportOcrPageMapper reportOcrPageMapper,
                               ReportParagraphAtomMapper reportParagraphAtomMapper,
                               ReportChunkDiagnosticMapper reportChunkDiagnosticMapper,
                               ReportHybridVectorStore hybridVectorStore,
                               ReportOcrParseService reportOcrParseService,
                               ReportSemanticChunkService reportSemanticChunkService,
                               ReportIngestFailureService reportIngestFailureService,
                               ReportQualityProperties reportQualityProperties,
                               ObjectProvider<ReportChunkTagJobService> reportChunkTagJobServiceProvider,
                               ObjectProvider<ReportVectorMetadataSyncJobService> metadataSyncJobServiceProvider,
                               ReportChunkFilterPolicy chunkFilterPolicy,
                               ReportVectorDocumentBuilder vectorDocumentBuilder,
                               ReportVectorBatchWriter vectorBatchWriter,
                               ReportAuthorService reportAuthorService) {
        this.reportDocumentMapper = reportDocumentMapper;
        this.reportChunkMapper = reportChunkMapper;
        this.reportOcrPageMapper = reportOcrPageMapper;
        this.reportParagraphAtomMapper = reportParagraphAtomMapper;
        this.reportChunkDiagnosticMapper = reportChunkDiagnosticMapper;
        this.hybridVectorStore = hybridVectorStore;
        this.reportOcrParseService = reportOcrParseService;
        this.reportSemanticChunkService = reportSemanticChunkService;
        this.reportIngestFailureService = reportIngestFailureService;
        this.reportChunkTagJobServiceProvider = reportChunkTagJobServiceProvider;
        this.chunkFilterPolicy = chunkFilterPolicy;
        this.vectorDocumentBuilder = vectorDocumentBuilder;
        this.vectorBatchWriter = vectorBatchWriter;
        this.reportAuthorService = reportAuthorService;
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
                reportChunkDiagnosticMapper, new SpringVectorStoreReportHybridVectorStore(vectorStoreProvider), reportOcrParseService, reportSemanticChunkService,
                reportIngestFailureService, reportQualityProperties, null, null,
                new ReportChunkFilterPolicy(reportQualityProperties),
                new ReportVectorDocumentBuilder(null),
                new ReportVectorBatchWriter(),
                null);
    }

    /**
     * @Description: 执行未携带作者的同步研报导入流程。
     * @Logic: 兼容旧同步入口，委托新入口并将作者文本置空。
     * @Param: file 上传文件；title 标题；source 来源；institution 机构；publishDate 发布日期。
     * @Return: 上传导入响应。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    @Transactional
    public ReportUploadRespDTO ingest(MultipartFile file, String title, String source, String institution, LocalDate publishDate) {
        // 兼容旧同步入口，未传作者时按空作者集合落库。
        return ingest(file, title, source, institution, publishDate, null);
    }

    /**
     * @Description: 执行同步研报导入流程。
     * @Logic: 先校验向量依赖和上传文件，再完成 OCR/切片、MySQL 主档与作者落库、质量数据落库和 Milvus 写入。
     * @Param: file 上传文件；title 标题；source 来源；institution 机构；publishDate 发布日期；authorTags 导入作者文本。
     * @Return: 上传导入响应。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Transactional
    public ReportUploadRespDTO ingest(MultipartFile file, String title, String source, String institution, LocalDate publishDate, String authorTags) {
        String stage = "VALIDATION";
        try {
            // 先确保向量存储可用，避免前置处理成功后才在落向量阶段失败导致补偿复杂化。
            ReportHybridVectorStore vectorStore = requireHybridVectorStore();
            validateFile(file);
            stage = "OCR_AND_CHUNK";
            // OCR 与语义切片绑定在同一预处理步骤，保证后续落库输入的一致性。
            IngestPreparation preparation = parseAndChunk(file);
            stage = "MYSQL";
            ReportDocument report = persistReportDocument(file, title, source, institution, publishDate);
            persistReportAuthors(report.getId(), authorTags);
            persistOcrQualityData(report, preparation.ocrResult());
            List<ReportChunk> persistedChildChunks = persistChunks(report, preparation.chunks());
            stage = "MILVUS";
            // 向量写入采用批量策略，降低单次请求体积并便于定位失败批次。
            vectorBatchWriter.write(vectorStore, vectorDocumentBuilder.buildVectorDocuments(report, persistedChildChunks));
            return buildUploadResp(null, report, persistedChildChunks.size());
        } catch (RuntimeException e) {
            // 记录当前失败阶段用于运维排障与后续重试策略判定。
            reportIngestFailureService.recordFailure(file, title, source, institution, stage, e);
            throw e;
        }
    }

    /**
     * @Description: 异步 OCR 阶段执行入口，负责主档入库与 OCR/段落数据落库。
     * @Logic: 已绑定 reportId 时直接返回；否则校验文件、解析 OCR、创建 report_document、保存作者和页/段落数据。
     * @Param: file 上传文件；title/source/institution/publishDate 报告元信息；authorTags 导入作者文本；existingReportId 已存在报告ID。
     * @Return: 报告ID。
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    @Transactional
    public Long ingestOcrStage(MultipartFile file,
                               String title,
                               String source,
                               String institution,
                               LocalDate publishDate,
                               String authorTags,
                               Long existingReportId) {
        if (existingReportId != null) {
            return existingReportId;
        }
        validateFile(file);
        ReportOcrParseResult ocrResult = reportOcrParseService.parseDetailed(file);
        ReportDocument report = persistReportDocument(file, title, source, institution, publishDate);
        persistReportAuthors(report.getId(), authorTags);
        persistOcrQualityData(report, ocrResult);
        return report.getId();
    }

    /**
     * @Description: 兼容未传作者的 OCR 阶段入口。
     * @Logic: 旧调用路径不携带作者时委托新入口并保存空作者集合。
     * @Param: file 上传文件；title 标题；source 来源；institution 机构；publishDate 发布日期；existingReportId 已绑定报告 ID。
     * @Return: 报告 ID。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public Long ingestOcrStage(MultipartFile file,
                               String title,
                               String source,
                               String institution,
                               LocalDate publishDate,
                               Long existingReportId) {
        // 委托新入口，未传作者时按空作者集合处理。
        return ingestOcrStage(file, title, source, institution, publishDate, null, existingReportId);
    }

    /**
     * @Description: 异步 Chunk 阶段执行入口，根据已落库段落生成并持久化语义切片。
     * @Logic: 已存在切片时直接返回避免重复；否则读取 paragraph atom，调用切片服务生成并写入 chunk 与诊断表。
     * @Param: reportId 报告ID。
     * @Return: 子切片数量。
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
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    @Transactional
    public int ingestVectorStage(Long reportId) {
        ReportHybridVectorStore vectorStore = requireHybridVectorStore();
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
        vectorBatchWriter.write(vectorStore, vectorDocumentBuilder.buildVectorDocuments(report, pendingChildren));
        for (ReportChunk chunk : pendingChildren) {
            reportChunkMapper.updateVectorStoredByChunkUid(chunk.getChunkUid(), true);
        }
        return pendingChildren.size();
    }

    /**
     * @Description: 获取 hybrid 向量存储。
     * @Logic: 生产构造器注入 Milvus 原生实现；旧测试构造器注入 Spring VectorStore 适配器。
     * @Param: 无。
     * @Return: hybrid 向量存储。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private ReportHybridVectorStore requireHybridVectorStore() {
        ReportHybridVectorStore vectorStore = hybridVectorStore;
        if (vectorStore == null) {
            throw new IllegalStateException("ReportHybridVectorStore is not configured. Check app.milvus-hybrid and embedding properties.");
        }
        return vectorStore;
    }

    /**
     * @Description: 校验输入参数与业务约束。
     * @Logic: 上传文件不能为空，避免 OCR 阶段拿到无效输入。
     * @Param: file 上传文件。
     * @Return: 无；文件为空时抛出异常。
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
     * @Logic: 先调用 OCR 服务生成页/段落结果，再调用语义切片服务生成 PARENT/CHILD 草案。
     * @Param: file 上传文件。
     * @Return: OCR 结果和语义切片结果。
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
     * @Description: 保存研报主档。
     * @Logic: 标题和来源缺失时使用上传文件名和 uploaded 兜底，并对元信息做乱码修复后写入 report_document。
     * @Param: file 上传文件；title 标题；source 来源；institution 机构；publishDate 发布日期。
     * @Return: 已写入 ID 的研报主档。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private ReportDocument persistReportDocument(MultipartFile file, String title, String source, String institution, LocalDate publishDate) {
        ReportDocument report = new ReportDocument();
        report.setTitle(repairMetadataText((title == null || title.isBlank()) ? file.getOriginalFilename() : title));
        report.setSource(repairMetadataText((source == null || source.isBlank()) ? "uploaded" : source));
        report.setInstitution(institution == null ? null : repairMetadataText(institution));
        report.setPublishDate(publishDate);
        report.setCreatedAt(Instant.now());
        reportDocumentMapper.insert(report);
        return report;
    }

    /**
     * @Description: 保存研报作者事实数据。
     * @Logic: 作者服务可用时写入 MySQL；旧测试构造未注入作者服务时跳过，生产构造会提供该服务。
     * @Param: reportId 研报主档 ID；authorTags 导入作者文本。
     * @Return: 无（仅写入作者表副作用）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private void persistReportAuthors(Long reportId, String authorTags) {
        // 兼容旧测试构造器：未注入作者服务时不执行作者表写入。
        if (reportAuthorService == null) {
            return;
        }
        // 委托作者服务解析、去重并写入 MySQL 作者事实表。
        reportAuthorService.saveImportAuthors(reportId, authorTags);
    }

    /**
     * @Description: 修复并裁剪导入元信息文本。
     * @Logic: null 保持 null；非空文本先 trim，再通过乱码修复工具处理常见 mojibake。
     * @Param: value 原始元信息文本。
     * @Return: 修复后的文本。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String repairMetadataText(String value) {
        if (value == null) {
            return null;
        }
        return TextEncodingRepairUtils.repairMojibake(value.trim());
    }

    /**
     * @Description: 保存 OCR 质量观测数据。
     * @Logic: 将页级 OCR 文本写入 report_ocr_page，并将段落 atom 写入 report_paragraph_atom。
     * @Param: report 研报主档；ocrResult OCR 解析结果。
     * @Return: 无（仅写入 OCR 观测表）。
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
     * @Description: 持久化 PARENT/CHILD 切片和诊断。
     * @Logic: 先过滤并保存 PARENT，再保存父切片保留下来的 CHILD；被过滤的切片只写诊断不写检索 chunk。
     * @Param: report 研报主档；chunks 语义切片结果。
     * @Return: 已持久化且待向量化的 CHILD chunk。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private List<ReportChunk> persistChunks(ReportDocument report, ReportSemanticChunks chunks) {
        List<ReportChunkSlice> filteredParents = chunks.parents().stream()
                .filter(chunkFilterPolicy::shouldKeepSlice)
                .toList();
        Map<Integer, String> parentUidByIndex = filteredParents.stream()
                .collect(Collectors.toMap(ReportChunkSlice::parentIndex, ignored -> newChunkUid()));

        for (ReportChunkSlice parentSlice : chunks.parents()) {
            String filterReason = chunkFilterPolicy.resolveFilterReason(parentSlice);
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
                .filter(chunkFilterPolicy::shouldKeepSlice)
                .filter(slice -> parentUidByIndex.containsKey(slice.parentIndex()))
                .toList();
        for (int i = 0; i < filteredChildren.size(); i++) {
            ReportChunkSlice childSlice = filteredChildren.get(i);
            String parentChunkUid = parentUidByIndex.get(childSlice.parentIndex());
            // CHILD 初次落库时必须标记为未向量化；仅在 ingestVectorStage 成功写入 Milvus 后再置为 true。
            ReportChunk childChunk = buildReportChunk(report, childSlice, newChunkUid(), parentChunkUid, false, null);
            childChunk.setChunkIndex(i);
            reportChunkMapper.insert(childChunk);
            persistChunkDiagnostic(report, childSlice, childChunk.getChunkUid(), parentChunkUid, true, null);
            enqueueChunkTagJob(childChunk);
            persistedChildChunks.add(childChunk);
        }
        for (ReportChunkSlice childSlice : chunks.children()) {
            String filterReason = chunkFilterPolicy.resolveFilterReason(childSlice);
            if (filterReason != null || !parentUidByIndex.containsKey(childSlice.parentIndex())) {
                String reason = filterReason == null ? "PARENT_FILTERED" : filterReason;
                persistChunkDiagnostic(report, childSlice, null, parentUidByIndex.get(childSlice.parentIndex()), false, reason);
            }
        }
        return persistedChildChunks;
    }

    /**
     * @Description: 构建 report_chunk 实体。
     * @Logic: 将语义切片字段投影为数据库实体，并写入过滤诊断和初始向量状态。
     * @Param: report 研报主档；slice 切片数据；chunkUid 当前切片 UID；parentChunkUid 父切片 UID；vectorStored 向量化状态；filterReason 过滤原因。
     * @Return: 待写入 report_chunk 的实体。
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
        chunk.setDiagnostics(chunkFilterPolicy.buildChunkDiagnostics(slice, filterReason));
        chunk.setVectorStored(vectorStored);
        chunk.setCreatedAt(Instant.now());
        return chunk;
    }

    /**
     * @Description: 保存切片诊断记录。
     * @Logic: 无论切片是否保留，都记录页码、段落、token、过滤原因和诊断文本，便于回看召回语料质量。
     * @Param: report 研报主档；slice 切片数据；chunkUid 当前切片 UID；parentChunkUid 父切片 UID；kept 是否保留；filterReason 过滤原因。
     * @Return: 无（仅写入 report_chunk_diagnostic）。
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
        diagnostic.setDiagnostics(chunkFilterPolicy.buildChunkDiagnostics(slice, filterReason));
        diagnostic.setChunkText(slice.text());
        diagnostic.setCreatedAt(Instant.now());
        reportChunkDiagnosticMapper.insert(diagnostic);
    }

    /**
     * @Description: 生成 chunk 唯一标识。
     * @Logic: 使用去横线 UUID 作为 MySQL chunkUid 和 Milvus 主键。
     * @Param: 无。
     * @Return: chunk 唯一标识。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String newChunkUid() {
        return UUID.randomUUID().toString().replace("-", "");
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
     * @Description: 构建同步上传响应。
     * @Logic: 返回报告 ID、标题、切片数量和固定成功消息，供旧同步入口使用。
     * @Param: jobId 导入任务 ID；report 研报主档；chunkCount 子切片数量。
     * @Return: 上传响应 DTO。
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
