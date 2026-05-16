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
public class ReportQualityQueryService {

    private final ReportOcrPageMapper reportOcrPageMapper;
    private final ReportParagraphAtomMapper reportParagraphAtomMapper;
    private final ReportChunkMapper reportChunkMapper;
    private final ReportChunkDiagnosticMapper reportChunkDiagnosticMapper;

    public ReportQualityQueryService(ReportOcrPageMapper reportOcrPageMapper,
                                     ReportParagraphAtomMapper reportParagraphAtomMapper,
                                     ReportChunkMapper reportChunkMapper,
                                     ReportChunkDiagnosticMapper reportChunkDiagnosticMapper) {
        this.reportOcrPageMapper = reportOcrPageMapper;
        this.reportParagraphAtomMapper = reportParagraphAtomMapper;
        this.reportChunkMapper = reportChunkMapper;
        this.reportChunkDiagnosticMapper = reportChunkDiagnosticMapper;
    }

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
