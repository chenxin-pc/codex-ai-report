package com.example.aimilvusweb.dto;

public record ReportUploadRespDTO(
        Long reportId,
        String title,
        int chunkCount,
        String message
) {
}
