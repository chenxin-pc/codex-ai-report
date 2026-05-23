package com.example.aimilvusweb.service;

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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportQualityQueryServiceTests类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
        ReportDocumentMapper reportDocumentMapper = mock(ReportDocumentMapper.class);
        ReportQualityQueryService service = new ReportQualityQueryService(
                ocrPageMapper,
                paragraphAtomMapper,
                chunkMapper,
                diagnosticMapper,
                reportDocumentMapper
        );

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

    @Test
    void shouldBuildChunkObservationPairs() {
        ReportOcrPageMapper ocrPageMapper = mock(ReportOcrPageMapper.class);
        ReportParagraphAtomMapper paragraphAtomMapper = mock(ReportParagraphAtomMapper.class);
        ReportChunkMapper chunkMapper = mock(ReportChunkMapper.class);
        ReportChunkDiagnosticMapper diagnosticMapper = mock(ReportChunkDiagnosticMapper.class);
        ReportDocumentMapper reportDocumentMapper = mock(ReportDocumentMapper.class);
        ReportQualityQueryService service = new ReportQualityQueryService(
                ocrPageMapper,
                paragraphAtomMapper,
                chunkMapper,
                diagnosticMapper,
                reportDocumentMapper
        );
        ReportDocument reportDocument = new ReportDocument();
        reportDocument.setTitle("测试研报");
        when(reportDocumentMapper.selectById(9L)).thenReturn(reportDocument);
        ReportParagraphAtom atom1 = new ReportParagraphAtom();
        atom1.setParagraphId(1);
        atom1.setParagraphText("第一段");
        ReportParagraphAtom atom2 = new ReportParagraphAtom();
        atom2.setParagraphId(2);
        atom2.setParagraphText("第二段");
        when(paragraphAtomMapper.selectByReportId(9L)).thenReturn(List.of(atom1, atom2));
        ReportChunk chunk = new ReportChunk();
        chunk.setChunkIndex(1);
        chunk.setChunkUid("chunk-1");
        chunk.setChunkType("CHILD");
        chunk.setStartParagraphId(1);
        chunk.setEndParagraphId(2);
        chunk.setChunkText("第一段\n第二段");
        when(chunkMapper.selectByReportId(9L)).thenReturn(List.of(chunk));

        var resp = service.getChunkObservationByReportId(9L);

        Assertions.assertEquals(1, resp.totalChunks());
        Assertions.assertEquals("测试研报", resp.reportTitle());
        Assertions.assertTrue(resp.chunkPairs().get(0).sameContent());
        Assertions.assertEquals("仅边界切分，无文本改写", resp.chunkPairs().get(0).differenceSummary());
        Assertions.assertEquals("第一段\n第二段", resp.chunkPairs().get(0).sourceParagraphText());
        Assertions.assertEquals("第一段\n第二段", resp.chunkPairs().get(0).chunkText());
    }
}
