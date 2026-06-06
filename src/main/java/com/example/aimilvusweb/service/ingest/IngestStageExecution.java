package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.entity.IngestJob;
import lombok.Getter;

import java.time.Instant;

/**
 * @Description: 单次阶段执行上下文，保存 claim 后的任务快照、尝试次数和阶段开始时间。
 * @Logic: 阶段模板在短事务 claim 后携带该对象执行外部动作，再交给短事务完成成功或失败收尾。
 * @Param: 无。
 * @Return: 阶段执行上下文对象，供执行器在事务外传递任务快照和计时信息。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Getter
public class IngestStageExecution {

    /** claim 后的最新任务快照，handler 会基于该对象执行阶段动作。 */
    private final IngestJob job;
    /** 当前阶段本次尝试次数。 */
    private final int attempt;
    /** 当前阶段开始处理时间，用于计算阶段事件耗时。 */
    private final Instant startedAt;

    /**
     * @Description: 创建阶段执行上下文。
     * @Logic: 保存任务、attempt 和开始时间，不复制任务对象，确保 OCR handler 回填 reportId 后成功事件可读取。
     * @Param: job 导入任务；attempt 当前尝试次数；startedAt 阶段开始时间。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public IngestStageExecution(IngestJob job, int attempt, Instant startedAt) {
        // 保存 claim 后任务快照，后续 handler 和事件记录都围绕同一对象工作。
        this.job = job;
        // 保存本次 attempt，失败重试和阶段事件都会读取该值。
        this.attempt = attempt;
        // 保存阶段开始时间，complete/fail 时用它计算 durationMs。
        this.startedAt = startedAt;
    }
}
