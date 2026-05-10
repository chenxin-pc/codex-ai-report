package com.example.aimilvusweb.dto;

import java.util.List;

public record LlmChunkPlanRespDTO(
        List<LlmChunkSegmentRespDTO> segments
) {
}
