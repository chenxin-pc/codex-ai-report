package com.example.aimilvusweb.enums;

/**
 * @Description: 投研输入意图枚举，区分完整分析、主题研究和不可分析输入。
 * @Logic: 推荐入口根据该枚举决定是否召回、是否降级和如何组织输出。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-24 00:45:00
 */
public enum QueryIntentEnum {
    /**
     * 输入已锚定到明确公司、股票代码或可直接分析的投研问题。
     */
    ANALYZE,

    /**
     * 输入属于行业、主题或产业链研究，可分析相关公司但不直接给出个股买卖建议。
     */
    THEME_RESEARCH,

    /**
     * 输入不具备投研分析语义，需短路返回引导语并跳过召回与模型生成。
     */
    REJECT
}
