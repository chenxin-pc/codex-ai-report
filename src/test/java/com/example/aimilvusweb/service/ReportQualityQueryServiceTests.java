package com.example.aimilvusweb.service;

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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    void shouldBuildSuccessfulIngestChainObservation() {
        ReportOcrPageMapper ocrPageMapper = mock(ReportOcrPageMapper.class);
        ReportParagraphAtomMapper paragraphAtomMapper = mock(ReportParagraphAtomMapper.class);
        ReportChunkMapper chunkMapper = mock(ReportChunkMapper.class);
        ReportChunkDiagnosticMapper diagnosticMapper = mock(ReportChunkDiagnosticMapper.class);
        ReportDocumentMapper reportDocumentMapper = mock(ReportDocumentMapper.class);
        ReportIngestStageEventMapper stageEventMapper = mock(ReportIngestStageEventMapper.class);
        ReportQualityQueryService service = new ReportQualityQueryService(
                ocrPageMapper,
                paragraphAtomMapper,
                chunkMapper,
                diagnosticMapper,
                reportDocumentMapper,
                stageEventMapper
        );
        ReportDocument reportDocument = new ReportDocument();
        reportDocument.setId(11L);
        reportDocument.setTitle("储能行业深度");
        reportDocument.setSource("券商研报");
        when(reportDocumentMapper.selectById(11L)).thenReturn(reportDocument);
        ReportOcrPage page = new ReportOcrPage();
        page.setPageNumber(1);
        page.setRawText("raw text");
        ReportParagraphAtom atom = new ReportParagraphAtom();
        atom.setParagraphId(1);
        atom.setParagraphText("段落文本");
        when(ocrPageMapper.selectByReportId(11L)).thenReturn(List.of(page));
        when(paragraphAtomMapper.selectByReportId(11L)).thenReturn(List.of(atom));
        ReportChunk parent = chunk("parent-1", "PARENT", 0, "父切片");
        ReportChunk child = chunk("child-1", "CHILD", 0, "子切片");
        child.setParentChunkUid("parent-1");
        child.setVectorStored(true);
        when(chunkMapper.selectByReportId(11L)).thenReturn(List.of(parent, child));
        when(diagnosticMapper.selectByReportId(11L)).thenReturn(List.of(keptDiagnostic("child-1")));
        when(stageEventMapper.selectTimelineByReportId(11L)).thenReturn(List.of(
                stageEvent("OCR", "SUCCEEDED", 1),
                stageEvent("CHUNK", "SUCCEEDED", 2),
                stageEvent("VECTOR", "SUCCEEDED", 3)
        ));

        var resp = service.getIngestChainObservation(11L);

        Assertions.assertEquals("SUCCEEDED", resp.overallStatus());
        Assertions.assertEquals(4, resp.stages().size());
        Assertions.assertEquals("OCR", resp.stages().get(1).stage());
        Assertions.assertEquals("1", resp.stages().get(1).summary().metrics().get(0).value());
        Assertions.assertEquals(1, resp.stages().get(2).details().parentChunks().size());
        Assertions.assertEquals(1, resp.stages().get(3).details().vectorCandidates().size());
    }

    @Test
    void shouldBuildParentChildTreeAndFilteredDiagnostics() {
        ReportQualityQueryService service = chainServiceWith(
                reportDocument(12L),
                List.of(),
                List.of(),
                List.of(chunk("parent-1", "PARENT", 0, "父切片"), child("child-1", "parent-1", false)),
                List.of(keptDiagnostic("child-1"), filteredDiagnostic()),
                List.of(stageEvent("CHUNK", "SUCCEEDED", 2))
        );

        var resp = service.getIngestChainObservation(12L);
        var chunkStage = resp.stages().stream().filter(stage -> "CHUNK".equals(stage.stage())).findFirst().orElseThrow();

        Assertions.assertEquals(1, chunkStage.details().parentChunks().size());
        Assertions.assertEquals("child-1", chunkStage.details().parentChunks().get(0).children().get(0).chunkUid());
        Assertions.assertEquals(1, chunkStage.details().filteredDiagnostics().size());
        Assertions.assertFalse(chunkStage.details().filteredDiagnostics().get(0).persisted());
    }

    @Test
    void shouldCountVectorStoredAndPendingChildren() {
        ReportQualityQueryService service = chainServiceWith(
                reportDocument(13L),
                List.of(),
                List.of(),
                List.of(chunk("parent-1", "PARENT", 0, "父切片"), child("child-1", "parent-1", true), child("child-2", "parent-1", false)),
                List.of(),
                List.of(stageEvent("VECTOR", "PENDING", 3))
        );

        var resp = service.getIngestChainObservation(13L);
        var vectorStage = resp.stages().stream().filter(stage -> "VECTOR".equals(stage.stage())).findFirst().orElseThrow();

        Assertions.assertEquals(2, vectorStage.details().vectorCandidates().size());
        Assertions.assertEquals("1", vectorStage.summary().metrics().get(1).value());
        Assertions.assertEquals("1", vectorStage.summary().metrics().get(2).value());
    }

    @Test
    void shouldReturnExplainableStateWhenStageEventsMissing() {
        ReportQualityQueryService service = chainServiceWith(
                reportDocument(14L),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        var resp = service.getIngestChainObservation(14L);

        Assertions.assertEquals("PROCESSING", resp.overallStatus());
        Assertions.assertEquals("OCR", resp.currentStage());
        Assertions.assertEquals("NOT_STARTED", resp.stages().get(1).status());
        Assertions.assertTrue(resp.stages().get(2).details().parentChunks().isEmpty());
    }

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

    @Test
    void shouldOnlyReturnUniqueChildChunksForObservation() {
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
        ReportParagraphAtom atom = new ReportParagraphAtom();
        atom.setParagraphId(1);
        atom.setParagraphText("第一段");
        when(paragraphAtomMapper.selectByReportId(10L)).thenReturn(List.of(atom));
        ReportChunk parent = chunk("parent-1", "PARENT", 0, "第一段");
        ReportChunk child = chunk("child-1", "CHILD", 1, "第一段");
        ReportChunk duplicatedChild = chunk("child-1", "CHILD", 1, "第一段");
        ReportChunk anotherChild = chunk("child-2", "CHILD", 2, "第二段");
        when(chunkMapper.selectByReportId(10L)).thenReturn(List.of(parent, child, duplicatedChild, anotherChild));

        var resp = service.getChunkObservationByReportId(10L);

        Assertions.assertEquals(2, resp.totalChunks());
        Assertions.assertEquals("child-1", resp.chunkPairs().get(0).chunkUid());
        Assertions.assertEquals("child-2", resp.chunkPairs().get(1).chunkUid());
    }

    private ReportChunk chunk(String chunkUid, String chunkType, int chunkIndex, String chunkText) {
        ReportChunk chunk = new ReportChunk();
        chunk.setChunkUid(chunkUid);
        chunk.setChunkType(chunkType);
        chunk.setChunkIndex(chunkIndex);
        chunk.setStartParagraphId(1);
        chunk.setEndParagraphId(1);
        chunk.setChunkText(chunkText);
        return chunk;
    }

    private ReportChunk child(String chunkUid, String parentChunkUid, boolean vectorStored) {
        ReportChunk child = chunk(chunkUid, "CHILD", 0, "子切片" + chunkUid);
        child.setParentChunkUid(parentChunkUid);
        child.setVectorStored(vectorStored);
        child.setTokenCount(80);
        child.setStartPageNumber(1);
        child.setEndPageNumber(1);
        return child;
    }

    private ReportChunkDiagnostic keptDiagnostic(String chunkUid) {
        ReportChunkDiagnostic diagnostic = new ReportChunkDiagnostic();
        diagnostic.setChunkUid(chunkUid);
        diagnostic.setChunkType("CHILD");
        diagnostic.setKept(true);
        diagnostic.setTokenCount(80);
        return diagnostic;
    }

    private ReportChunkDiagnostic filteredDiagnostic() {
        ReportChunkDiagnostic diagnostic = new ReportChunkDiagnostic();
        diagnostic.setChunkType("CHILD");
        diagnostic.setKept(false);
        diagnostic.setFilterReason("LOW_TOKEN_COUNT");
        diagnostic.setChunkText("短文本");
        diagnostic.setTokenCount(8);
        return diagnostic;
    }

    private ReportIngestStageEvent stageEvent(String stage, String status, int sequence) {
        ReportIngestStageEvent event = new ReportIngestStageEvent();
        event.setJobUid("job-1");
        event.setReportId(1L);
        event.setStage(stage);
        event.setStatus(status);
        event.setAttempt(1);
        event.setDurationMs(100L);
        event.setModelName(stage + "-model");
        event.setCreatedAt(Instant.parse("2026-05-30T00:00:0" + sequence + "Z"));
        if (status.contains("FAILED")) {
            event.setErrorCode(stage + "_FAILED");
            event.setErrorMessageShort("failed");
        }
        return event;
    }

    private ReportDocument reportDocument(Long reportId) {
        ReportDocument reportDocument = new ReportDocument();
        reportDocument.setId(reportId);
        reportDocument.setTitle("测试研报" + reportId);
        return reportDocument;
    }

    private ReportQualityQueryService chainServiceWith(ReportDocument reportDocument,
                                                       List<ReportOcrPage> pages,
                                                       List<ReportParagraphAtom> atoms,
                                                       List<ReportChunk> chunks,
                                                       List<ReportChunkDiagnostic> diagnostics,
                                                       List<ReportIngestStageEvent> events) {
        ReportOcrPageMapper ocrPageMapper = mock(ReportOcrPageMapper.class);
        ReportParagraphAtomMapper paragraphAtomMapper = mock(ReportParagraphAtomMapper.class);
        ReportChunkMapper chunkMapper = mock(ReportChunkMapper.class);
        ReportChunkDiagnosticMapper diagnosticMapper = mock(ReportChunkDiagnosticMapper.class);
        ReportDocumentMapper reportDocumentMapper = mock(ReportDocumentMapper.class);
        ReportIngestStageEventMapper stageEventMapper = mock(ReportIngestStageEventMapper.class);
        when(reportDocumentMapper.selectById(reportDocument.getId())).thenReturn(reportDocument);
        when(ocrPageMapper.selectByReportId(reportDocument.getId())).thenReturn(pages);
        when(paragraphAtomMapper.selectByReportId(reportDocument.getId())).thenReturn(atoms);
        when(chunkMapper.selectByReportId(reportDocument.getId())).thenReturn(chunks);
        when(diagnosticMapper.selectByReportId(reportDocument.getId())).thenReturn(diagnostics);
        when(stageEventMapper.selectTimelineByReportId(reportDocument.getId())).thenReturn(events);
        return new ReportQualityQueryService(
                ocrPageMapper,
                paragraphAtomMapper,
                chunkMapper,
                diagnosticMapper,
                reportDocumentMapper,
                stageEventMapper
        );
    }
}
