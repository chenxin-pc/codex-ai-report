package com.example.aimilvusweb.dto;

import java.util.List;

/**
 * @Description: RecommendRespDTO类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public record RecommendRespDTO(
        String query,
        List<TopResultRespDTO> top5,
        String analysis,
        String recommendation,
        List<String> risks,
        List<String> citations
) {
}
