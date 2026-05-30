package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.entity.IngestJob;
import com.example.aimilvusweb.entity.ReportIngestStageEvent;
import com.example.aimilvusweb.enums.IngestStageStatusEnum;
import com.example.aimilvusweb.repository.IngestJobMapper;
import com.example.aimilvusweb.repository.ReportIngestStageEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * @Description: 入库阶段短事务服务，负责 claim、成功收尾、失败收尾和阶段事件落库。
 * @Logic: 将状态更新与事件写入放在短事务中，外部 OCR、LLM 和 Milvus 调用由阶段模板在事务外执行。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Service
public class IngestStageTransactionService {

    /** 任务 Mapper，用于读取任务和更新阶段状态。 */
    private final IngestJobMapper ingestJobMapper;
    /** 阶段事件 Mapper，用于记录每次阶段尝试。 */
    private final ReportIngestStageEventMapper stageEventMapper;
    /** 重试策略，用于失败收尾时判断退避或最终失败。 */
    private final IngestStageRetryPolicy retryPolicy;

    /**
     * @Description: 初始化阶段短事务服务。
     * @Logic: 保存任务 Mapper、事件 Mapper 和重试策略，供阶段执行模板调用。
     * @Param: ingestJobMapper 任务 Mapper；stageEventMapper 阶段事件 Mapper；retryPolicy 重试策略。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public IngestStageTransactionService(IngestJobMapper ingestJobMapper,
                                         ReportIngestStageEventMapper stageEventMapper,
                                         IngestStageRetryPolicy retryPolicy) {
        this.ingestJobMapper = ingestJobMapper;
        this.stageEventMapper = stageEventMapper;
        this.retryPolicy = retryPolicy;
    }

    /**
     * @Description: 占用单个阶段任务。
     * @Logic: 读取最新任务、校验前置依赖、累加 attempt、标记 PROCESSING 并立即持久化。
     * @Param: definition 阶段定义；jobUid 任务唯一标识。
     * @Return: 阶段执行上下文，包含任务、attempt 和开始时间。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Transactional
    public IngestStageExecution claim(IngestStageDefinition definition, String jobUid) {
        IngestJob job = requireJob(jobUid);
        definition.ensurePrerequisitesSatisfied(job);
        Instant startedAt = Instant.now();
        int attempt = definition.increaseAttempt(job);
        definition.markStatus(job, IngestStageStatusEnum.PROCESSING);
        job.setUpdatedAt(Instant.now());
        ingestJobMapper.updateStatusAndAttempt(job);
        return new IngestStageExecution(job, attempt, startedAt);
    }

    /**
     * @Description: 完成阶段成功收尾。
     * @Logic: 标记阶段成功、清理历史错误、允许下一阶段尽快调度，并写入成功阶段事件。
     * @Param: definition 阶段定义；execution 阶段执行上下文；outputSize 阶段输出数量。
     * @Return: 无（仅更新任务状态并写入阶段事件）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Transactional
    public void complete(IngestStageDefinition definition, IngestStageExecution execution, Integer outputSize) {
        IngestJob job = execution.getJob();
        Instant now = Instant.now();
        definition.markStatus(job, IngestStageStatusEnum.SUCCEEDED);
        job.setLastErrorCode(null);
        job.setLastErrorMessage(null);
        job.setNextRunAt(now);
        job.setUpdatedAt(now);
        ingestJobMapper.updateStatusAndAttempt(job);
        saveStageEvent(definition, execution, IngestStageStatusEnum.SUCCEEDED.code(), null, null, outputSize);
    }

    /**
     * @Description: 完成阶段失败收尾。
     * @Logic: 可重试且未达上限时回到 PENDING 并设置退避时间，否则标记 FAILED_FINAL，同时写入失败阶段事件。
     * @Param: definition 阶段定义；execution 阶段执行上下文；ex 阶段异常。
     * @Return: 无（仅更新任务状态并写入阶段事件）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Transactional
    public void fail(IngestStageDefinition definition, IngestStageExecution execution, Exception ex) {
        IngestJob job = execution.getJob();
        boolean canRetry = retryPolicy.canRetry(ex, execution.getAttempt());
        Instant now = Instant.now();
        definition.markStatus(job, canRetry ? IngestStageStatusEnum.PENDING : IngestStageStatusEnum.FAILED_FINAL);
        job.setLastErrorCode(retryPolicy.errorCode(ex));
        job.setLastErrorMessage(retryPolicy.shortMessage(ex));
        job.setNextRunAt(canRetry ? now.plusMillis(retryPolicy.backoffMs(execution.getAttempt())) : now);
        job.setUpdatedAt(now);
        ingestJobMapper.updateStatusAndAttempt(job);
        String eventStatus = canRetry ? IngestStageStatusEnum.PENDING.code() : IngestStageStatusEnum.FAILED_FINAL.code();
        saveStageEvent(definition, execution, eventStatus, job.getLastErrorCode(), job.getLastErrorMessage(), null);
    }

    /**
     * @Description: 按任务唯一标识读取任务。
     * @Logic: 未命中时抛出参数异常，避免后续阶段使用空任务继续处理。
     * @Param: jobUid 任务唯一标识。
     * @Return: 任务实体。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private IngestJob requireJob(String jobUid) {
        IngestJob job = ingestJobMapper.selectByJobUid(jobUid);
        if (job == null) {
            throw new IllegalArgumentException("Ingest job not found: " + jobUid);
        }
        return job;
    }

    /**
     * @Description: 写入阶段事件记录。
     * @Logic: 补齐 OCR 阶段可能刚绑定的 reportId，记录状态、模型、耗时、错误摘要和 traceId。
     * @Param: definition 阶段定义；execution 阶段执行上下文；status 阶段事件状态；errorCode 错误码；errorMessage 错误摘要；outputSize 输出数量。
     * @Return: 无（仅写入阶段事件）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private void saveStageEvent(IngestStageDefinition definition,
                                IngestStageExecution execution,
                                String status,
                                String errorCode,
                                String errorMessage,
                                Integer outputSize) {
        IngestJob job = execution.getJob();
        if (job.getReportId() == null) {
            IngestJob latest = ingestJobMapper.selectByJobUid(job.getJobUid());
            if (latest != null) {
                job.setReportId(latest.getReportId());
            }
        }
        Instant finishedAt = Instant.now();
        ReportIngestStageEvent event = new ReportIngestStageEvent();
        event.setJobUid(job.getJobUid());
        event.setReportId(job.getReportId());
        event.setReportTitleSnapshot(job.getReportTitleSnapshot());
        event.setTitleSearchKey(job.getTitleSearchKey());
        event.setStage(definition.code());
        event.setAttempt(execution.getAttempt());
        event.setStatus(status);
        event.setModelName(definition.getModelName());
        event.setInputSize(1);
        event.setOutputSize(outputSize);
        event.setStartedAt(execution.getStartedAt());
        event.setFinishedAt(finishedAt);
        event.setDurationMs(Math.max(0L, finishedAt.toEpochMilli() - execution.getStartedAt().toEpochMilli()));
        event.setErrorCode(errorCode);
        event.setErrorMessageShort(errorMessage);
        event.setTraceId(job.getJobUid() + "-" + definition.code() + "-" + execution.getAttempt());
        event.setCreatedAt(finishedAt);
        stageEventMapper.insert(event);
    }
}
