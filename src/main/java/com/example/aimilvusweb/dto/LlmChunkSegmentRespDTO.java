package com.example.aimilvusweb.dto;

public record LlmChunkSegmentRespDTO(
        Integer startParagraphId,
        Integer endParagraphId,
        String topic,
        String segmentType,
        Double confidence
) {
}
