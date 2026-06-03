package com.example.aimilvusweb.service.retrieval;

/**
 * @Description: 推荐证据上下文来源类型，用于标记召回结果最终使用的上下文构建方式。
 * @Logic: PARENT 聚合和 CHILD 扩展策略根据父切片长度、缺失情况和窗口配置选择具体枚举值。
 * @Param: 无。
 * @Return: 无（枚举仅承载上下文来源分类）。
 * @author: cx
 * @Date: 2026-06-03 00:00:00
 */
public enum EvidenceContextType {
    /** 使用完整 PARENT chunk 作为证据上下文。 */
    FULL_PARENT,
    /** 使用按 token 上限截断后的 PARENT chunk 作为证据上下文。 */
    TRUNCATED_PARENT,
    /** 使用命中 CHILD 及其同父邻近 CHILD 拼接出的窗口作为证据上下文。 */
    CHILD_WINDOW,
    /** 无法扩展 PARENT 时使用命中的 CHILD 文本作为兜底证据上下文。 */
    CHILD_FALLBACK
}
