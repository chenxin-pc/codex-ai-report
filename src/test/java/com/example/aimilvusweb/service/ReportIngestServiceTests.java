package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.util.SemanticChunkUtils.ReportChunkSlice;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.repository.ReportChunkDiagnosticMapper;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import com.example.aimilvusweb.repository.ReportOcrPageMapper;
import com.example.aimilvusweb.repository.ReportParagraphAtomMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Method;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportIngestServiceTests类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
    void shouldFilterLowValueSegmentTypes() throws Exception {
        ReportIngestService ingestService = newIngestService(new ReportQualityProperties());
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

        String filterReason = resolveFilterReason(ingestService, slice);

        Assertions.assertEquals("EXCLUDED_SEGMENT_TYPE:ANALYST_DECLARATION", filterReason);
    }

    @Test
    void shouldKeepFinancialTableDespiteLowHanRatio() throws Exception {
        ReportQualityProperties properties = new ReportQualityProperties();
        properties.getChunk().setMinSliceTokenCount(10);
        ReportIngestService ingestService = newIngestService(properties);
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

        String filterReason = resolveFilterReason(ingestService, slice);

        Assertions.assertNull(filterReason);
    }

    /**
     * @Description: 执行newIngestService相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private ReportIngestService newIngestService(ReportQualityProperties properties) {
        return new ReportIngestService(
                mock(ReportDocumentMapper.class),
                mock(ReportChunkMapper.class),
                mock(ReportOcrPageMapper.class),
                mock(ReportParagraphAtomMapper.class),
                mock(ReportChunkDiagnosticMapper.class),
                mock(ObjectProvider.class),
                mock(ReportOcrParseService.class),
                mock(ReportSemanticChunkService.class),
                mock(ReportIngestFailureService.class),
                properties
        );
    }

    /**
     * @Description: 根据上下文解析并确定最终值。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String resolveFilterReason(ReportIngestService ingestService, ReportChunkSlice slice) throws Exception {
        Method method = ReportIngestService.class.getDeclaredMethod("resolveFilterReason", ReportChunkSlice.class);
        method.setAccessible(true);
        return (String) method.invoke(ingestService, slice);
    }
}
