package com.example.aimilvusweb.dto;

import java.util.List;

public record RecommendRespDTO(
        String query,
        List<TopResultRespDTO> top5,
        String analysis,
        String recommendation,
        List<String> risks,
        List<String> citations
) {
}
