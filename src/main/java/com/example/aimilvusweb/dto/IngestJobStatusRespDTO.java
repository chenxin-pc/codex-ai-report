package com.example.aimilvusweb.dto;

import java.time.Instant;

/**
 * @Description: 异步导入任务状态响应，返回各阶段状态、重试计数和下次调度时间。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:15:00
 */
public record IngestJobStatusRespDTO(
        String jobId,
        Long reportId,
        String title,
        String ocrStatus,
        String chunkStatus,
        String vectorStatus,
        int ocrAttemptCount,
        int chunkAttemptCount,
        int vectorAttemptCount,
        Instant nextRunAt,
        String lastErrorCode,
        String lastErrorMessage
) {
}
