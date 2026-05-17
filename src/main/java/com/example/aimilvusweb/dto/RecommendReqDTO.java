package com.example.aimilvusweb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @Description: RecommendReqDTO类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public record RecommendReqDTO(
        @NotBlank(message = "query is required")
        String query
) {
}
