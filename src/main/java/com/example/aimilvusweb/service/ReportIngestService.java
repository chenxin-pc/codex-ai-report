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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
/**
 * @Description: ReportIngestService类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class ReportIngestService {
    private static final int EMBEDDING_BATCH_SIZE = 10;
    /**
     * @Description: 执行of相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static final Set<String> EXCLUDED_SECTION_KEYWORDS = Set.of(
            "免责声明", "免责条款", "法律声明", "分析师承诺", "评级说明", "投资评级说明", "风险披露",
            "分析师声明", "研究所联系方式", "联系方式", "券商简介", "机构介绍", "中邮证券研究所"
    );
    /**
     * @Description: 执行of相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static final Set<String> EXCLUDED_SEGMENT_TYPES = Set.of(
            "DISCLAIMER", "ANALYST_DECLARATION", "BROKER_PROFILE", "CONTACT_INFO", "LAYOUT_NOISE"
    );
    /**
     * @Description: 执行of相关业务处理。
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

    /**
     * @Description: 初始化ReportIngestService依赖与运行所需组件。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
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
    }

    /**
     * @Description: 执行ingest相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    @Transactional
    public ReportUploadRespDTO ingest(MultipartFile file, String title, String source, String institution, LocalDate publishDate) {
        String stage = "VALIDATION";
        try {
            VectorStore vectorStore = requireVectorStore();
            validateFile(file);
            stage = "OCR_AND_CHUNK";
            IngestPreparation preparation = parseAndChunk(file);
            stage = "MYSQL";
            ReportDocument report = persistReportDocument(file, title, source, institution, publishDate);
            persistOcrQualityData(report, preparation.ocrResult());
            List<ReportChunk> persistedChildChunks = persistChunks(report, preparation.chunks());
            stage = "MILVUS";
            addVectorDocumentsInBatches(vectorStore, buildVectorDocuments(report, persistedChildChunks));
            return buildUploadResp(report, persistedChildChunks.size());
        } catch (RuntimeException e) {
            reportIngestFailureService.recordFailure(file, title, source, institution, stage, e);
            throw e;
        }
    }

    /**
     * @Description: 执行requireVectorStore相关业务处理。
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
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String newChunkUid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * @Description: 构建目标对象或请求数据。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private List<Document> buildVectorDocuments(ReportDocument report, List<ReportChunk> chunks) {
        List<Document> vectorDocuments = new ArrayList<>();
        for (ReportChunk chunk : chunks) {
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
            vectorDocuments.add(new Document(chunk.getChunkText(), metadata));
        }
        return vectorDocuments;
    }

    /**
     * @Description: 向目标集合追加处理结果。
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
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private boolean shouldKeepSlice(ReportChunkSlice slice) {
        return resolveFilterReason(slice) == null;
    }

    /**
     * @Description: 根据上下文解析并确定最终值。
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
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private ReportUploadRespDTO buildUploadResp(ReportDocument report, int chunkCount) {
        return new ReportUploadRespDTO(report.getId(), report.getTitle(), chunkCount, "Upload and ingest completed");
    }

    private record IngestPreparation(ReportOcrParseResult ocrResult, ReportSemanticChunks chunks) {
    }
}
