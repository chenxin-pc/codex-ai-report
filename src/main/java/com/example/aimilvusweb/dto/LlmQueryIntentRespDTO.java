package com.example.aimilvusweb.dto;

/**
 * @Description: LLM 输入意图分类响应，承载兜底分类的结构化结果。
 * @Logic: 模型仅返回分类字段，不承载投研分析正文，低置信结果由服务层保守拒绝。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-24 00:45:00
 */
public record LlmQueryIntentRespDTO(
        /** 模型判定的输入意图字符串，应映射到 QueryIntentEnum。 */
        String intent,
        /** 模型判定置信度，用于低置信时保守拒绝。 */
        Double confidence,
        /** 模型给出的分类理由。 */
        String reason,
        /** 模型归一化后的query，作为后续召回输入。 */
        String normalizedQuery
) {
}
