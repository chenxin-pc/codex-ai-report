package com.example.aimilvusweb.dto;

/**
 * @Description: ReportUploadRespDTO类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public record ReportUploadRespDTO(
        Long reportId,
        String title,
        int chunkCount,
        String message
) {
}
