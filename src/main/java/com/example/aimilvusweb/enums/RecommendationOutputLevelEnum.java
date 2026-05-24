package com.example.aimilvusweb.enums;

/**
 * @Description: 推荐输出等级枚举，控制投研响应从拒答到完整分析的输出强度。
 * @Logic: 服务层基于输入意图和证据质量选择等级，Prompt 和前端据此展示降级原因。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-24 00:45:00
 */
public enum RecommendationOutputLevelEnum {
    /**
     * 输入不可分析，直接返回投研模式引导语。
     */
    L0_REJECT,

    /**
     * 召回证据缺失、低相关或污染，只允许输出证据不足说明。
     */
    L1_INSUFFICIENT_OR_POLLUTED,

    /**
     * 主题或行业研究输出，可给出研究对象但禁止高确定性投资建议。
     */
    L2_THEME_RESEARCH,

    /**
     * 输入与证据均通过校验，可输出完整投研分析。
     */
    L3_FULL_ANALYSIS
}
