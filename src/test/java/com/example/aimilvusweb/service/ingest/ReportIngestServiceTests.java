package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.infra.chunk.SemanticChunkUtils.ReportChunkSlice;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportDocument;
import com.example.aimilvusweb.repository.ReportChunkDiagnosticMapper;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import com.example.aimilvusweb.repository.ReportOcrPageMapper;
import com.example.aimilvusweb.repository.ReportParagraphAtomMapper;
import com.example.aimilvusweb.infra.chunk.ReportSemanticChunkService;
import com.example.aimilvusweb.service.ingest.ReportChunkFilterPolicy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 研报导入服务测试，验证 OCR、切片、向量写入和失败记录等导入阶段行为。
 * @Logic: 使用 mock Mapper、OCR 服务、语义切片服务和向量存储构造导入链路，断言落库、过滤、补偿和异常分支。
 * @Param: 无。
 * @Return: 无（测试类仅通过断言验证导入服务行为）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
class ReportIngestServiceTests {

    @Test
    void shouldNotPersistChunksOrVectorsWhenOcrFails() {
        ReportDocumentMapper documentMapper = mock(ReportDocumentMapper.class);
        ReportChunkMapper chunkMapper = mock(ReportChunkMapper.class);
        ReportOcrPageMapper ocrPageMapper = mock(ReportOcrPageMapper.class);
        ReportParagraphAtomMapper paragraphAtomMapper = mock(ReportParagraphAtomMapper.class);
        ReportChunkDiagnosticMapper chunkDiagnosticMapper = mock(ReportChunkDiagnosticMapper.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ReportOcrParseService ocrParseService = mock(ReportOcrParseService.class);
        ReportSemanticChunkService semanticChunkService = mock(ReportSemanticChunkService.class);
        ReportIngestFailureService failureService = mock(ReportIngestFailureService.class);
        ReportIngestService ingestService = new ReportIngestService(
                documentMapper,
                chunkMapper,
                ocrPageMapper,
                paragraphAtomMapper,
                chunkDiagnosticMapper,
                vectorStoreProvider,
                ocrParseService,
                semanticChunkService,
                failureService,
                new ReportQualityProperties()
        );
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "demo".getBytes());

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(ocrParseService.parseDetailed(file)).thenThrow(new IllegalArgumentException("OCR recognized no usable report text"));

        IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ingestService.ingest(file, "title", "source", "institution", null)
        );

        Assertions.assertTrue(exception.getMessage().contains("OCR recognized no usable report text"));
        verify(documentMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(chunkMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(vectorStore, never()).add(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void shouldFilterLowValueSegmentTypes() {
        ReportChunkFilterPolicy filterPolicy = new ReportChunkFilterPolicy(new ReportQualityProperties());
        ReportChunkSlice slice = new ReportChunkSlice(
                "CHILD",
                0,
                0,
                "分析师声明",
                "Section: 分析师声明\n\n撰写此报告的分析师承诺无利害关系。",
                80,
                1,
                2,
                4,
                4,
                "ANALYST_DECLARATION"
        );

        String filterReason = filterPolicy.resolveFilterReason(slice);

        Assertions.assertEquals("EXCLUDED_SEGMENT_TYPE:ANALYST_DECLARATION", filterReason);
    }

    @Test
    void shouldKeepFinancialTableDespiteLowHanRatio() {
        ReportQualityProperties properties = new ReportQualityProperties();
        properties.getChunk().setMinSliceTokenCount(10);
        ReportChunkFilterPolicy filterPolicy = new ReportChunkFilterPolicy(properties);
        ReportChunkSlice slice = new ReportChunkSlice(
                "CHILD",
                0,
                0,
                "盈利预测和财务指标",
                "Section: 盈利预测和财务指标\n\n营业收入 | 2026E 338 | 2027E 447 | 2028E 606\nEPS | -0.06 | 0.04 | 0.09\nP/E | -1180.18 | 2112.68 | 819.47",
                45,
                10,
                12,
                2,
                2,
                "FINANCIAL_TABLE"
        );

        String filterReason = filterPolicy.resolveFilterReason(slice);

        Assertions.assertNull(filterReason);
    }

    @Test
    void shouldResolveAllChunkFilterReasons() {
        ReportQualityProperties properties = new ReportQualityProperties();
        properties.getChunk().setMinSliceTokenCount(10);
        ReportChunkFilterPolicy filterPolicy = new ReportChunkFilterPolicy(properties);

        Assertions.assertEquals("EXCLUDED_SECTION:免责声明",
                filterPolicy.resolveFilterReason(slice("免责声明", "这是一段较长的免责声明内容，用于触发章节路径过滤而不是短文本过滤。", 80, "OTHER")));
        Assertions.assertEquals("LOW_TOKEN_COUNT:5",
                filterPolicy.resolveFilterReason(slice("正文", "这是一段足够长的正文内容，但 token 数故意设置得非常低。", 5, "OTHER")));
        Assertions.assertTrue(filterPolicy.resolveFilterReason(slice("正文", "短文本内容", 80, "OTHER")).startsWith("SHORT_TEXT:"));
        Assertions.assertTrue(filterPolicy.resolveFilterReason(slice("正文", "1234567890".repeat(8), 80, "OTHER")).startsWith("LOW_HAN_RATIO:"));
        Assertions.assertTrue(filterPolicy.resolveFilterReason(slice("正文", "新能源".repeat(10) + "1234567890".repeat(7), 100, "OTHER")).startsWith("HIGH_NOISE_RATIO:"));
    }

    @Test
    void shouldOnlyVectorizePendingChildChunks() {
        ReportDocumentMapper documentMapper = mock(ReportDocumentMapper.class);
        ReportChunkMapper chunkMapper = mock(ReportChunkMapper.class);
        ReportOcrPageMapper ocrPageMapper = mock(ReportOcrPageMapper.class);
        ReportParagraphAtomMapper paragraphAtomMapper = mock(ReportParagraphAtomMapper.class);
        ReportChunkDiagnosticMapper chunkDiagnosticMapper = mock(ReportChunkDiagnosticMapper.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ReportIngestService ingestService = new ReportIngestService(
                documentMapper,
                chunkMapper,
                ocrPageMapper,
                paragraphAtomMapper,
                chunkDiagnosticMapper,
                vectorStoreProvider,
                mock(ReportOcrParseService.class),
                mock(ReportSemanticChunkService.class),
                mock(ReportIngestFailureService.class),
                new ReportQualityProperties()
        );
        ReportDocument report = new ReportDocument();
        report.setId(1L);
        report.setTitle("测试报告");
        report.setSource("uploaded");
        report.setPublishDate(LocalDate.of(2026, 5, 30));
        ReportChunk parent = chunk("parent-1", "PARENT", null, false);
        ReportChunk storedChild = chunk("child-stored", "CHILD", "parent-1", true);
        ReportChunk pendingChild = chunk("child-pending", "CHILD", "parent-1", false);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(documentMapper.selectById(1L)).thenReturn(report);
        when(chunkMapper.selectByReportId(1L)).thenReturn(List.of(parent, storedChild, pendingChild));

        int count = ingestService.ingestVectorStage(1L);

        Assertions.assertEquals(1, count);
        ArgumentCaptor<List> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documentsCaptor.capture());
        Assertions.assertEquals(1, documentsCaptor.getValue().size());
        verify(chunkMapper).updateVectorStoredByChunkUid("child-pending", true);
        verify(chunkMapper, never()).updateVectorStoredByChunkUid("child-stored", true);
        verify(chunkMapper, never()).updateVectorStoredByChunkUid("parent-1", true);
        verify(vectorStore, times(1)).add(anyList());
    }

    @Test
    void shouldNotMarkChunksStoredWhenVectorWriteFails() {
        ReportDocumentMapper documentMapper = mock(ReportDocumentMapper.class);
        ReportChunkMapper chunkMapper = mock(ReportChunkMapper.class);
        ReportOcrPageMapper ocrPageMapper = mock(ReportOcrPageMapper.class);
        ReportParagraphAtomMapper paragraphAtomMapper = mock(ReportParagraphAtomMapper.class);
        ReportChunkDiagnosticMapper chunkDiagnosticMapper = mock(ReportChunkDiagnosticMapper.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ReportIngestService ingestService = new ReportIngestService(
                documentMapper,
                chunkMapper,
                ocrPageMapper,
                paragraphAtomMapper,
                chunkDiagnosticMapper,
                vectorStoreProvider,
                mock(ReportOcrParseService.class),
                mock(ReportSemanticChunkService.class),
                mock(ReportIngestFailureService.class),
                new ReportQualityProperties()
        );
        ReportDocument report = new ReportDocument();
        report.setId(1L);
        report.setTitle("测试报告");
        report.setSource("uploaded");
        ReportChunk pendingChild = chunk("child-pending", "CHILD", "parent-1", false);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(documentMapper.selectById(1L)).thenReturn(report);
        when(chunkMapper.selectByReportId(1L)).thenReturn(List.of(pendingChild));
        doThrow(new IllegalStateException("milvus down")).when(vectorStore).add(anyList());

        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> ingestService.ingestVectorStage(1L));

        Assertions.assertTrue(exception.getMessage().contains("milvus down"));
        verify(chunkMapper, never()).updateVectorStoredByChunkUid("child-pending", true);
    }

    /**
     * @Description: 构造切片测试对象。
     * @Logic: 使用固定 parent/chunk 索引与段落页码，只暴露过滤测试关注的 section/text/token/segmentType。
     * @Param: sectionPath 章节路径；text 切片文本；tokenCount token 数；segmentType 语义段类型。
     * @Return: 切片候选。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private ReportChunkSlice slice(String sectionPath, String text, int tokenCount, String segmentType) {
        return new ReportChunkSlice("CHILD", 0, 0, sectionPath, text, tokenCount, 1, 2, 1, 1, segmentType);
    }

    /**
     * @Description: 构造 chunk 测试对象。
     * @Logic: 填充向量阶段筛选和 Document 构建需要的基础字段。
     * @Param: chunkUid 切片唯一标识；chunkType 切片类型；parentChunkUid 父切片标识；vectorStored 向量状态。
     * @Return: chunk 实体。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private ReportChunk chunk(String chunkUid, String chunkType, String parentChunkUid, Boolean vectorStored) {
        ReportChunk chunk = new ReportChunk();
        chunk.setId(Math.abs(chunkUid.hashCode()) + 1L);
        chunk.setReportId(1L);
        chunk.setChunkUid(chunkUid);
        chunk.setParentChunkUid(parentChunkUid);
        chunk.setChunkType(chunkType);
        chunk.setChunkIndex(0);
        chunk.setSectionPath("正文");
        chunk.setChunkText("这是一段用于向量入库测试的正文内容。");
        chunk.setTokenCount(80);
        chunk.setStartParagraphId(1);
        chunk.setEndParagraphId(2);
        chunk.setStartPageNumber(1);
        chunk.setEndPageNumber(1);
        chunk.setVectorStored(vectorStored);
        return chunk;
    }
}
