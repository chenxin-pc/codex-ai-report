package com.example.aimilvusweb.dto;

import java.util.List;

public record LlmRecommendRespDTO(
        String analysis,
        String recommendation,
        List<String> risks,
        List<String> citations
) {
}
