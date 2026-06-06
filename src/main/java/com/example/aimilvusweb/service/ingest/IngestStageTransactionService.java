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
 * @Param: 无。
 * @Return: 阶段事务服务对象，负责任务状态与阶段事件的原子持久化。
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
        // 保存任务 Mapper，claim/complete/fail 都需要更新 ingest_job。
        this.ingestJobMapper = ingestJobMapper;
        // 保存阶段事件 Mapper，每次阶段尝试都要记录一条事件。
        this.stageEventMapper = stageEventMapper;
        // 保存重试策略，失败收尾时用它判断 PENDING 或 FAILED_FINAL。
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
        // 读取最新任务，避免基于过期状态执行阶段。
        IngestJob job = requireJob(jobUid);
        // 校验前置阶段状态，防止 CHUNK/VECTOR 被提前执行。
        definition.ensurePrerequisitesSatisfied(job);
        // 记录 claim 时间，作为本阶段事件的 startedAt。
        Instant startedAt = Instant.now();
        // 累加当前阶段 attempt，并写回任务快照。
        int attempt = definition.increaseAttempt(job);
        // 将当前阶段状态标记为 PROCESSING，表示调度线程已占用任务。
        definition.markStatus(job, IngestStageStatusEnum.PROCESSING);
        // 更新时间用于后台观测和后续调度排序。
        job.setUpdatedAt(Instant.now());
        // 持久化状态和 attempt，形成短事务 claim 边界。
        ingestJobMapper.updateStatusAndAttempt(job);
        // 返回执行上下文，后续耗时动作在事务外使用该对象。
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
        // 取出 handler 使用过的任务快照，OCR 阶段可能已在该对象上回填 reportId。
        IngestJob job = execution.getJob();
        // 成功收尾使用同一个 now，保证 nextRunAt 与 updatedAt 一致。
        Instant now = Instant.now();
        // 当前阶段成功，状态写入对应 ocr/chunk/vector 字段。
        definition.markStatus(job, IngestStageStatusEnum.SUCCEEDED);
        // 阶段成功后清空上一轮失败错误码。
        job.setLastErrorCode(null);
        // 阶段成功后清空上一轮失败摘要。
        job.setLastErrorMessage(null);
        // 下一阶段可以立刻被调度，nextRunAt 设置为当前时间。
        job.setNextRunAt(now);
        // 更新时间反映成功收尾时刻。
        job.setUpdatedAt(now);
        // 持久化成功状态和调度时间。
        ingestJobMapper.updateStatusAndAttempt(job);
        // 写入成功阶段事件，记录输出规模和耗时。
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
        // 取出失败阶段对应的任务快照。
        IngestJob job = execution.getJob();
        // 根据异常类型和 attempt 判断本次失败是否还能重试。
        boolean canRetry = retryPolicy.canRetry(ex, execution.getAttempt());
        // 失败收尾使用同一个 now，保证重试时间和更新时间一致。
        Instant now = Instant.now();
        // 可重试时回到 PENDING；不可重试时进入 FAILED_FINAL。
        definition.markStatus(job, canRetry ? IngestStageStatusEnum.PENDING : IngestStageStatusEnum.FAILED_FINAL);
        // 保存错误码，方便列表和观测接口快速定位异常类型。
        job.setLastErrorCode(retryPolicy.errorCode(ex));
        // 保存短错误消息，避免完整堆栈或长文本进入任务表。
        job.setLastErrorMessage(retryPolicy.shortMessage(ex));
        // 可重试时设置退避后的下次运行时间；最终失败时不再延迟。
        job.setNextRunAt(canRetry ? now.plusMillis(retryPolicy.backoffMs(execution.getAttempt())) : now);
        // 更新时间反映失败收尾时刻。
        job.setUpdatedAt(now);
        // 持久化失败状态、错误摘要和下次调度时间。
        ingestJobMapper.updateStatusAndAttempt(job);
        // 阶段事件状态与任务状态保持一致，便于按阶段分析重试历史。
        String eventStatus = canRetry ? IngestStageStatusEnum.PENDING.code() : IngestStageStatusEnum.FAILED_FINAL.code();
        // 写入失败阶段事件，记录错误码、摘要和耗时。
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
        // 按 jobUid 从任务表读取导入任务。
        IngestJob job = ingestJobMapper.selectByJobUid(jobUid);
        // 任务不存在时直接失败，避免后续空对象导致更模糊的异常。
        if (job == null) {
            throw new IllegalArgumentException("Ingest job not found: " + jobUid);
        }
        // 返回可用于阶段状态推进的任务实体。
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
        // 读取本次阶段执行使用的任务对象。
        IngestJob job = execution.getJob();
        // OCR 阶段 reportId 可能由 handler 刚绑定，当前快照为空时回查最新任务。
        if (job.getReportId() == null) {
            // 回查最新任务，补齐 OCR 阶段成功后生成的 reportId。
            IngestJob latest = ingestJobMapper.selectByJobUid(job.getJobUid());
            // 最新任务存在时将 reportId 写回当前事件上下文。
            if (latest != null) {
                job.setReportId(latest.getReportId());
            }
        }
        // 记录阶段完成时间，用于事件 finishedAt 和 durationMs。
        Instant finishedAt = Instant.now();
        // 创建阶段事件实体，后续逐字段填充观测所需信息。
        ReportIngestStageEvent event = new ReportIngestStageEvent();
        // 绑定任务唯一标识，作为事件与 ingest_job 的主关联键。
        event.setJobUid(job.getJobUid());
        // 绑定报告 ID，OCR 成功后可用于跳转报告详情。
        event.setReportId(job.getReportId());
        // 保存报告标题快照，避免任务标题后续变化影响历史事件阅读。
        event.setReportTitleSnapshot(job.getReportTitleSnapshot());
        // 保存标题搜索键，支持观测列表按标题检索。
        event.setTitleSearchKey(job.getTitleSearchKey());
        // 保存阶段编码，例如 OCR、CHUNK 或 VECTOR。
        event.setStage(definition.code());
        // 保存本次尝试次数，和重试事件一一对应。
        event.setAttempt(execution.getAttempt());
        // 保存事件状态，成功、待重试或最终失败。
        event.setStatus(status);
        // 保存阶段使用的模型名，用于分析 OCR/LLM/Embedding 配置效果。
        event.setModelName(definition.getModelName());
        // 当前阶段以单个任务为输入，inputSize 固定记录为 1。
        event.setInputSize(1);
        // 保存阶段输出规模，例如 OCR 绑定 1 篇报告、CHUNK 输出 CHILD 数。
        event.setOutputSize(outputSize);
        // 保存阶段开始时间，来自 claim 阶段执行上下文。
        event.setStartedAt(execution.getStartedAt());
        // 保存阶段结束时间。
        event.setFinishedAt(finishedAt);
        // 计算非负耗时，避免系统时间抖动产生负数。
        event.setDurationMs(Math.max(0L, finishedAt.toEpochMilli() - execution.getStartedAt().toEpochMilli()));
        // 保存错误码；成功事件为 null。
        event.setErrorCode(errorCode);
        // 保存短错误消息；成功事件为 null。
        event.setErrorMessageShort(errorMessage);
        // 生成可读 traceId，包含任务、阶段和 attempt，方便日志/事件串联。
        event.setTraceId(job.getJobUid() + "-" + definition.code() + "-" + execution.getAttempt());
        // 保存事件创建时间，和 finishedAt 保持一致。
        event.setCreatedAt(finishedAt);
        // 写入阶段事件表。
        stageEventMapper.insert(event);
    }
}
