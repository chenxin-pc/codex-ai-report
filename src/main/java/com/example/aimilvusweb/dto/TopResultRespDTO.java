package com.example.aimilvusweb.dto;

public record TopResultRespDTO(
        Double score,
        String title,
        String chunkText,
        String source
) {
}
