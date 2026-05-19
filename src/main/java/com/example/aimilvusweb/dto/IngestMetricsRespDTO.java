package com.example.aimilvusweb.dto;

/**
 * @Description: 导入观测指标响应，汇总各阶段积压与最终失败数量。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:15:00
 */
public record IngestMetricsRespDTO(
        int ocrPending,
        int chunkPending,
        int vectorPending,
        int ocrFailedFinal,
        int chunkFailedFinal,
        int vectorFailedFinal
) {
}
