package com.example.aimilvusweb.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * @Description: 研报观测列表响应，包含基础元信息与最新导入状态。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:59:00
 */
public record ReportObservationRespDTO(
        Long reportId,
        String title,
        String source,
        String institution,
        LocalDate publishDate,
        Instant createdAt,
        String jobUid,
        String ocrStatus,
        String chunkStatus,
        String vectorStatus,
        String lastErrorCode,
        Instant updatedAt
) {
}
