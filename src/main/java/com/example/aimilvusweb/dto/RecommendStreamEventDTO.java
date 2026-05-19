package com.example.aimilvusweb.dto;

import java.util.List;

/**
 * @Description: 推荐流式输出事件数据，承载阶段状态、模型增量和检索证据。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
