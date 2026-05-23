package com.example.aimilvusweb.dto;

import java.util.List;

/**
 * @Description: 研报切片观测响应，返回切片前原文与切片后结果的对应关系。
 * @Logic: 每条切片记录包含段落范围、原文拼接文本与切片文本，便于人工核对切片质量。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-20 23:40:00
 */
public record ReportChunkObservationRespDTO(
        Long reportId,
        String reportTitle,
        int totalChunks,
        List<ChunkPairRespDTO> chunkPairs
) {
    public record ChunkPairRespDTO(
            Integer chunkIndex,
            String chunkUid,
            String chunkType,
            String sectionPath,
            Integer startParagraphId,
            Integer endParagraphId,
            Integer startPageNumber,
            Integer endPageNumber,
            Integer tokenCount,
            String filterReason,
            boolean sameContent,
            String differenceSummary,
            String sourceParagraphText,
            String chunkText
    ) {
    }
}
