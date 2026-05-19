package com.example.aimilvusweb.dto;

import java.time.Instant;

/**
 * @Description: 导入阶段事件响应，返回阶段执行时长、模型、输入输出规模和错误摘要。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:15:00
 */
public record ReportIngestStageEventRespDTO(
        String jobId,
        Long reportId,
        String reportTitle,
        String stage,
        int attempt,
        String status,
        String modelName,
        Integer inputSize,
        Integer outputSize,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String errorCode,
        String errorMessageShort,
        String traceId,
        Instant createdAt
) {
}
