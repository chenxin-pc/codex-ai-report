package com.example.aimilvusweb.dto;

import jakarta.validation.constraints.NotBlank;

public record RecommendReqDTO(
        @NotBlank(message = "query is required")
        String query
) {
}
