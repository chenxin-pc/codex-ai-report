package com.example.aimilvusweb.service;

import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportChunkDiagnostic;
import com.example.aimilvusweb.entity.ReportOcrPage;
import com.example.aimilvusweb.entity.ReportParagraphAtom;
import com.example.aimilvusweb.repository.ReportChunkDiagnosticMapper;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportOcrPageMapper;
import com.example.aimilvusweb.repository.ReportParagraphAtomMapper;
import org.springframework.stereotype.Service;

import java.util.List;

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
                                     ReportChunkDiagnosticMapper reportChunkDiagnosticMapper) {
        this.reportOcrPageMapper = reportOcrPageMapper;
        this.reportParagraphAtomMapper = reportParagraphAtomMapper;
        this.reportChunkMapper = reportChunkMapper;
        this.reportChunkDiagnosticMapper = reportChunkDiagnosticMapper;
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
