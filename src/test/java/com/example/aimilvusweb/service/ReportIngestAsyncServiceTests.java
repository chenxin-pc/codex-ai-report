package com.example.aimilvusweb.service;

import com.example.aimilvusweb.config.ReportIngestAsyncProperties;
import com.example.aimilvusweb.dto.IngestMetricsRespDTO;
import com.example.aimilvusweb.dto.ReportUploadRespDTO;
import com.example.aimilvusweb.entity.IngestJob;
import com.example.aimilvusweb.repository.IngestJobMapper;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import com.example.aimilvusweb.repository.ReportIngestStageEventMapper;
import com.example.aimilvusweb.service.ingest.IngestStageExecutor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportIngestAsyncServiceTests类，负责异步导入服务提交与指标汇总能力验证。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:15:00
 */
class ReportIngestAsyncServiceTests {

    @Test
    void shouldSubmitJobAndReturnJobId() {
        IngestJobMapper ingestJobMapper = mock(IngestJobMapper.class);
        ReportIngestStageEventMapper stageEventMapper = mock(ReportIngestStageEventMapper.class);
        ReportDocumentMapper reportDocumentMapper = mock(ReportDocumentMapper.class);
        IngestStageExecutor ingestStageExecutor = mock(IngestStageExecutor.class);
        ReportIngestAsyncProperties properties = new ReportIngestAsyncProperties();
        properties.setSpoolDir("reports/test-ingest-spool");
        ReportIngestAsyncService service = new ReportIngestAsyncService(
                ingestJobMapper,
                stageEventMapper,
                reportDocumentMapper,
                ingestStageExecutor,
                properties,
                "qwen-vl-ocr-latest",
                "qwen-plus-latest",
                "text-embedding-v3"
        );
        MockMultipartFile file = new MockMultipartFile("file", "demo.pdf", "application/pdf", "demo".getBytes());
        when(ingestJobMapper.insert(any(IngestJob.class))).thenReturn(1);

        ReportUploadRespDTO resp = service.submit(file, "测试标题", "source", "inst", null, "STORAGE:储能", "POWER:电力", "宁德时代", "300750.SZ");

        Assertions.assertNotNull(resp.jobId());
        Assertions.assertFalse(resp.jobId().isBlank());
        Assertions.assertEquals("测试标题", resp.title());
        ArgumentCaptor<IngestJob> captor = ArgumentCaptor.forClass(IngestJob.class);
        org.mockito.Mockito.verify(ingestJobMapper).insert(captor.capture());
        Assertions.assertEquals("STORAGE:储能", captor.getValue().getThemeTags());
        Assertions.assertEquals("POWER:电力", captor.getValue().getIndustryTags());
        Assertions.assertEquals("宁德时代", captor.getValue().getCompanyTags());
        Assertions.assertEquals("300750.SZ", captor.getValue().getTickerTags());
    }

    @Test
    void shouldAggregateMetrics() {
        IngestJobMapper ingestJobMapper = mock(IngestJobMapper.class);
        ReportIngestStageEventMapper stageEventMapper = mock(ReportIngestStageEventMapper.class);
        ReportDocumentMapper reportDocumentMapper = mock(ReportDocumentMapper.class);
        IngestStageExecutor ingestStageExecutor = mock(IngestStageExecutor.class);
        ReportIngestAsyncProperties properties = new ReportIngestAsyncProperties();
        ReportIngestAsyncService service = new ReportIngestAsyncService(
                ingestJobMapper,
                stageEventMapper,
                reportDocumentMapper,
                ingestStageExecutor,
                properties,
                "qwen-vl-ocr-latest",
                "qwen-plus-latest",
                "text-embedding-v3"
        );
        when(ingestJobMapper.countByStageStatus("OCR", "PENDING")).thenReturn(3);
        when(ingestJobMapper.countByStageStatus("CHUNK", "PENDING")).thenReturn(2);
        when(ingestJobMapper.countByStageStatus("VECTOR", "PENDING")).thenReturn(1);
        when(ingestJobMapper.countByStageStatus("OCR", "FAILED_FINAL")).thenReturn(1);
        when(ingestJobMapper.countByStageStatus("CHUNK", "FAILED_FINAL")).thenReturn(0);
        when(ingestJobMapper.countByStageStatus("VECTOR", "FAILED_FINAL")).thenReturn(4);

        IngestMetricsRespDTO metrics = service.getMetrics();

        Assertions.assertEquals(3, metrics.ocrPending());
        Assertions.assertEquals(2, metrics.chunkPending());
        Assertions.assertEquals(1, metrics.vectorPending());
        Assertions.assertEquals(1, metrics.ocrFailedFinal());
        Assertions.assertEquals(0, metrics.chunkFailedFinal());
        Assertions.assertEquals(4, metrics.vectorFailedFinal());
    }
}
