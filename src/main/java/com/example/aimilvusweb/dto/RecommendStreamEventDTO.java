package com.example.aimilvusweb.dto;

import java.util.List;

/**
 * @Description: 推荐流式输出事件数据，承载阶段状态、模型增量和检索证据。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public record RecommendStreamEventDTO(
        String stage,
        String message,
        String text,
        List<TopResultRespDTO> top5
) {
}
