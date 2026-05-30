package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.config.ReportIngestAsyncProperties;
import com.example.aimilvusweb.entity.IngestJob;
import com.example.aimilvusweb.entity.ReportIngestStageEvent;
import com.example.aimilvusweb.enums.IngestStageEnum;
import com.example.aimilvusweb.enums.IngestStageStatusEnum;
import com.example.aimilvusweb.repository.IngestJobMapper;
import com.example.aimilvusweb.repository.ReportIngestStageEventMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: IngestStageExecutorTests类，验证入库阶段执行模板的成功、失败、重试和前置依赖行为。
 * @Logic: 使用 mock Mapper 与 handler 驱动阶段执行，断言状态更新顺序、事件内容和失败退避语义。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
class IngestStageExecutorTests {

    @Test
    void shouldCompleteOcrChunkAndVectorStagesWithEvents() throws Exception {
        assertSuccessfulStage(IngestStageEnum.OCR, baseJob(), "ocr-model");
        IngestJob chunkJob = baseJob();
        chunkJob.setOcrStatus(IngestStageStatusEnum.SUCCEEDED.code());
        chunkJob.setReportId(10L);
        assertSuccessfulStage(IngestStageEnum.CHUNK, chunkJob, "chunk-model");
        IngestJob vectorJob = baseJob();
        vectorJob.setOcrStatus(IngestStageStatusEnum.SUCCEEDED.code());
        vectorJob.setChunkStatus(IngestStageStatusEnum.SUCCEEDED.code());
        vectorJob.setReportId(11L);
        assertSuccessfulStage(IngestStageEnum.VECTOR, vectorJob, "vector-model");
    }

    @Test
    void shouldRetryRetryableFailureBeforeMaxAttempts() throws Exception {
        IngestJobMapper ingestJobMapper = mock(IngestJobMapper.class);
        ReportIngestStageEventMapper eventMapper = mock(ReportIngestStageEventMapper.class);
        ReportIngestAsyncProperties properties = new ReportIngestAsyncProperties();
        properties.setBackoffFirstMs(1234L);
        IngestStageRetryPolicy retryPolicy = new IngestStageRetryPolicy(properties);
        IngestStageTransactionService txService = new IngestStageTransactionService(ingestJobMapper, eventMapper, retryPolicy);
        IngestStageHandler handler = mock(IngestStageHandler.class);
        when(handler.stage()).thenReturn(IngestStageEnum.OCR);
        when(handler.execute(any(IngestJob.class))).thenThrow(new IllegalStateException("timeout from OCR"));
        IngestStageExecutor executor = new IngestStageExecutor(List.of(handler), txService);
        IngestJob job = baseJob();
        job.setReportId(12L);
        when(ingestJobMapper.selectByJobUid("job-1")).thenReturn(job);
        List<String> statusUpdates = captureStatusUpdates(ingestJobMapper, IngestStageEnum.OCR);

        executor.process(new IngestStageDefinition(IngestStageEnum.OCR, "ocr-model"), "job-1");

        Assertions.assertEquals(List.of(
                IngestStageStatusEnum.PROCESSING.code(),
                IngestStageStatusEnum.PENDING.code()), statusUpdates);
        Assertions.assertEquals("IllegalStateException", job.getLastErrorCode());
        Assertions.assertTrue(job.getLastErrorMessage().contains("timeout"));
        Assertions.assertTrue(job.getNextRunAt().isAfter(job.getUpdatedAt()) || job.getNextRunAt().equals(job.getUpdatedAt().plusMillis(1234L)));
        ArgumentCaptor<ReportIngestStageEvent> eventCaptor = ArgumentCaptor.forClass(ReportIngestStageEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        Assertions.assertEquals(IngestStageStatusEnum.PENDING.code(), eventCaptor.getValue().getStatus());
        Assertions.assertEquals("IllegalStateException", eventCaptor.getValue().getErrorCode());
    }

    @Test
    void shouldMarkFinalFailureWhenRetryableErrorReachesMaxAttempts() throws Exception {
        IngestJobMapper ingestJobMapper = mock(IngestJobMapper.class);
        ReportIngestStageEventMapper eventMapper = mock(ReportIngestStageEventMapper.class);
        ReportIngestAsyncProperties properties = new ReportIngestAsyncProperties();
        properties.setMaxAttempts(3);
        IngestStageRetryPolicy retryPolicy = new IngestStageRetryPolicy(properties);
        IngestStageTransactionService txService = new IngestStageTransactionService(ingestJobMapper, eventMapper, retryPolicy);
        IngestStageHandler handler = mock(IngestStageHandler.class);
        when(handler.stage()).thenReturn(IngestStageEnum.VECTOR);
        when(handler.execute(any(IngestJob.class))).thenThrow(new IllegalStateException("503 temporary unavailable"));
        IngestStageExecutor executor = new IngestStageExecutor(List.of(handler), txService);
        IngestJob job = baseJob();
        job.setOcrStatus(IngestStageStatusEnum.SUCCEEDED.code());
        job.setChunkStatus(IngestStageStatusEnum.SUCCEEDED.code());
        job.setVectorAttemptCount(2);
        job.setReportId(13L);
        when(ingestJobMapper.selectByJobUid("job-1")).thenReturn(job);
        List<String> statusUpdates = captureStatusUpdates(ingestJobMapper, IngestStageEnum.VECTOR);

        executor.process(new IngestStageDefinition(IngestStageEnum.VECTOR, "vector-model"), "job-1");

        Assertions.assertEquals(List.of(
                IngestStageStatusEnum.PROCESSING.code(),
                IngestStageStatusEnum.FAILED_FINAL.code()), statusUpdates);
        ArgumentCaptor<ReportIngestStageEvent> eventCaptor = ArgumentCaptor.forClass(ReportIngestStageEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        Assertions.assertEquals(IngestStageStatusEnum.FAILED_FINAL.code(), eventCaptor.getValue().getStatus());
    }

    @Test
    void shouldMarkFinalFailureForNonRetryableError() throws Exception {
        IngestJobMapper ingestJobMapper = mock(IngestJobMapper.class);
        ReportIngestStageEventMapper eventMapper = mock(ReportIngestStageEventMapper.class);
        IngestStageRetryPolicy retryPolicy = new IngestStageRetryPolicy(new ReportIngestAsyncProperties());
        IngestStageTransactionService txService = new IngestStageTransactionService(ingestJobMapper, eventMapper, retryPolicy);
        IngestStageHandler handler = mock(IngestStageHandler.class);
        when(handler.stage()).thenReturn(IngestStageEnum.CHUNK);
        when(handler.execute(any(IngestJob.class))).thenThrow(new IllegalArgumentException("bad chunk input"));
        IngestStageExecutor executor = new IngestStageExecutor(List.of(handler), txService);
        IngestJob job = baseJob();
        job.setOcrStatus(IngestStageStatusEnum.SUCCEEDED.code());
        job.setReportId(14L);
        when(ingestJobMapper.selectByJobUid("job-1")).thenReturn(job);
        List<String> statusUpdates = captureStatusUpdates(ingestJobMapper, IngestStageEnum.CHUNK);

        executor.process(new IngestStageDefinition(IngestStageEnum.CHUNK, "chunk-model"), "job-1");

        Assertions.assertEquals(List.of(
                IngestStageStatusEnum.PROCESSING.code(),
                IngestStageStatusEnum.FAILED_FINAL.code()), statusUpdates);
        Assertions.assertEquals("IllegalArgumentException", job.getLastErrorCode());
        ArgumentCaptor<ReportIngestStageEvent> eventCaptor = ArgumentCaptor.forClass(ReportIngestStageEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        Assertions.assertEquals(IngestStageStatusEnum.FAILED_FINAL.code(), eventCaptor.getValue().getStatus());
    }

    @Test
    void shouldRejectChunkOrVectorWhenPrerequisitesMissing() {
        IngestJobMapper ingestJobMapper = mock(IngestJobMapper.class);
        ReportIngestStageEventMapper eventMapper = mock(ReportIngestStageEventMapper.class);
        IngestStageRetryPolicy retryPolicy = new IngestStageRetryPolicy(new ReportIngestAsyncProperties());
        IngestStageTransactionService txService = new IngestStageTransactionService(ingestJobMapper, eventMapper, retryPolicy);
        IngestStageHandler handler = mock(IngestStageHandler.class);
        when(handler.stage()).thenReturn(IngestStageEnum.CHUNK);
        IngestStageExecutor executor = new IngestStageExecutor(List.of(handler), txService);
        IngestJob job = baseJob();
        when(ingestJobMapper.selectByJobUid("job-1")).thenReturn(job);

        Assertions.assertThrows(IllegalStateException.class,
                () -> executor.process(new IngestStageDefinition(IngestStageEnum.CHUNK, "chunk-model"), "job-1"));

        verify(ingestJobMapper, never()).updateStatusAndAttempt(any(IngestJob.class));
        verify(eventMapper, never()).insert(any(ReportIngestStageEvent.class));
    }

    /**
     * @Description: 断言单个阶段成功路径。
     * @Logic: 创建独立 mock 依赖，执行阶段模板后校验 PROCESSING/SUCCEEDED 状态顺序和阶段事件核心字段。
     * @Param: stage 阶段枚举；job 任务快照；modelName 模型名。
     * @Return: 无（断言失败时抛出测试异常）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private void assertSuccessfulStage(IngestStageEnum stage, IngestJob job, String modelName) throws Exception {
        IngestJobMapper ingestJobMapper = mock(IngestJobMapper.class);
        ReportIngestStageEventMapper eventMapper = mock(ReportIngestStageEventMapper.class);
        IngestStageRetryPolicy retryPolicy = new IngestStageRetryPolicy(new ReportIngestAsyncProperties());
        IngestStageTransactionService txService = new IngestStageTransactionService(ingestJobMapper, eventMapper, retryPolicy);
        IngestStageHandler handler = mock(IngestStageHandler.class);
        when(handler.stage()).thenReturn(stage);
        when(handler.execute(any(IngestJob.class))).thenReturn(7);
        IngestStageExecutor executor = new IngestStageExecutor(List.of(handler), txService);
        when(ingestJobMapper.selectByJobUid("job-1")).thenReturn(job);
        List<String> statusUpdates = captureStatusUpdates(ingestJobMapper, stage);

        executor.process(new IngestStageDefinition(stage, modelName), "job-1");

        Assertions.assertEquals(List.of(
                IngestStageStatusEnum.PROCESSING.code(),
                IngestStageStatusEnum.SUCCEEDED.code()), statusUpdates);
        Assertions.assertEquals(1, stage.getAttemptCount(job));
        Assertions.assertNull(job.getLastErrorCode());
        Assertions.assertNull(job.getLastErrorMessage());
        ArgumentCaptor<ReportIngestStageEvent> eventCaptor = ArgumentCaptor.forClass(ReportIngestStageEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        ReportIngestStageEvent event = eventCaptor.getValue();
        Assertions.assertEquals(stage.code(), event.getStage());
        Assertions.assertEquals(modelName, event.getModelName());
        Assertions.assertEquals(IngestStageStatusEnum.SUCCEEDED.code(), event.getStatus());
        Assertions.assertEquals(7, event.getOutputSize());
    }

    /**
     * @Description: 捕获任务状态更新顺序。
     * @Logic: 在 Mapper 更新回调中读取当前阶段状态并保存快照，避免 Mockito 捕获可变对象引用导致断言失真。
     * @Param: ingestJobMapper 任务 Mapper；stage 当前阶段。
     * @Return: 状态更新快照列表。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private List<String> captureStatusUpdates(IngestJobMapper ingestJobMapper, IngestStageEnum stage) {
        List<String> statusUpdates = new ArrayList<>();
        doAnswer(invocation -> {
            IngestJob job = invocation.getArgument(0);
            statusUpdates.add(stage.getStatus(job));
            return 1;
        }).when(ingestJobMapper).updateStatusAndAttempt(any(IngestJob.class));
        return statusUpdates;
    }

    /**
     * @Description: 构造默认任务快照。
     * @Logic: 默认三阶段均为 PENDING 且 attempt 为 0，测试按阶段需要再覆盖前置成功状态。
     * @Param: 无。
     * @Return: 默认导入任务实体。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private IngestJob baseJob() {
        IngestJob job = new IngestJob();
        job.setId(1L);
        job.setJobUid("job-1");
        job.setReportTitleSnapshot("测试报告");
        job.setTitleSearchKey("测试报告");
        job.setFilePath("reports/test.pdf");
        job.setOcrStatus(IngestStageStatusEnum.PENDING.code());
        job.setChunkStatus(IngestStageStatusEnum.PENDING.code());
        job.setVectorStatus(IngestStageStatusEnum.PENDING.code());
        job.setOcrAttemptCount(0);
        job.setChunkAttemptCount(0);
        job.setVectorAttemptCount(0);
        job.setNextRunAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        return job;
    }
}
