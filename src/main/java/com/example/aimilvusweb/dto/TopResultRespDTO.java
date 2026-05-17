package com.example.aimilvusweb.dto;

/**
 * @Description: TopResultRespDTO类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public record TopResultRespDTO(
        Double score,
        String title,
        String chunkText,
        String source
) {
}
