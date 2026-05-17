package com.example.aimilvusweb.service;

import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportChunkDiagnostic;
import com.example.aimilvusweb.entity.ReportOcrPage;
import com.example.aimilvusweb.entity.ReportParagraphAtom;
import com.example.aimilvusweb.repository.ReportChunkDiagnosticMapper;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportOcrPageMapper;
import com.example.aimilvusweb.repository.ReportParagraphAtomMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportQualityQueryServiceTests类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
class ReportQualityQueryServiceTests {

    @Test
    void shouldAggregateQualityDataByReportId() {
        ReportOcrPageMapper ocrPageMapper = mock(ReportOcrPageMapper.class);
        ReportParagraphAtomMapper paragraphAtomMapper = mock(ReportParagraphAtomMapper.class);
        ReportChunkMapper chunkMapper = mock(ReportChunkMapper.class);
        ReportChunkDiagnosticMapper diagnosticMapper = mock(ReportChunkDiagnosticMapper.class);
        ReportQualityQueryService service = new ReportQualityQueryService(ocrPageMapper, paragraphAtomMapper, chunkMapper, diagnosticMapper);

        when(ocrPageMapper.selectByReportId(7L)).thenReturn(List.of(new ReportOcrPage()));
        when(paragraphAtomMapper.selectByReportId(7L)).thenReturn(List.of(new ReportParagraphAtom()));
        when(chunkMapper.selectByReportId(7L)).thenReturn(List.of(new ReportChunk()));
        when(diagnosticMapper.selectByReportId(7L)).thenReturn(List.of(new ReportChunkDiagnostic()));

        ReportQualityQueryService.ReportQualityData data = service.getByReportId(7L);

        Assertions.assertEquals(1, data.ocrPages().size());
        Assertions.assertEquals(1, data.paragraphAtoms().size());
        Assertions.assertEquals(1, data.chunks().size());
        Assertions.assertEquals(1, data.chunkDiagnostics().size());
    }
}
