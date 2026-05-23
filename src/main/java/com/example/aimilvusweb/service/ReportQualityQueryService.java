package com.example.aimilvusweb.service;

import com.example.aimilvusweb.dto.ReportChunkObservationRespDTO;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportChunkDiagnostic;
import com.example.aimilvusweb.entity.ReportDocument;
import com.example.aimilvusweb.entity.ReportOcrPage;
import com.example.aimilvusweb.entity.ReportParagraphAtom;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import com.example.aimilvusweb.repository.ReportChunkDiagnosticMapper;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportOcrPageMapper;
import com.example.aimilvusweb.repository.ReportParagraphAtomMapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final ReportOcrPageMapper reportOcrPageMapper;
    private final ReportParagraphAtomMapper reportParagraphAtomMapper;
    private final ReportChunkMapper reportChunkMapper;
    private final ReportChunkDiagnosticMapper reportChunkDiagnosticMapper;
    private final ReportDocumentMapper reportDocumentMapper;

    /**
     * @Description: 初始化ReportQualityQueryService依赖与运行所需组件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public ReportQualityQueryService(ReportOcrPageMapper reportOcrPageMapper,
                                     ReportParagraphAtomMapper reportParagraphAtomMapper,
                                     ReportChunkMapper reportChunkMapper,
                                     ReportChunkDiagnosticMapper reportChunkDiagnosticMapper,
                                     ReportDocumentMapper reportDocumentMapper) {
        this.reportOcrPageMapper = reportOcrPageMapper;
        this.reportParagraphAtomMapper = reportParagraphAtomMapper;
        this.reportChunkMapper = reportChunkMapper;
        this.reportChunkDiagnosticMapper = reportChunkDiagnosticMapper;
        this.reportDocumentMapper = reportDocumentMapper;
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
        List<ReportChunkObservationRespDTO.ChunkPairRespDTO> pairs = reportChunkMapper.selectByReportId(reportId).stream()
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
