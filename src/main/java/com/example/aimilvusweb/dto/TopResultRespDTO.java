package com.example.aimilvusweb.dto;

import java.util.List;

/**
 * @Description: 召回Top结果响应，承载前端展示的切片文本和父子切片定位信息。
 * @Logic: chunkText 展示命中的子切片文本，parentContext 保留父切片上下文，便于排查召回证据来源。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public record TopResultRespDTO(
        /** 向量召回相似度分数。 */
        Double score,
        /** 召回证据所属研报标题。 */
        String title,
        /** 命中的子切片文本，作为前端主要展示文本。 */
        String chunkText,
        /** 召回证据来源标识。 */
        String source,
        /** 切片所在章节路径。 */
        String sectionPath,
        /** 命中子切片唯一标识。 */
        String chunkUid,
        /** 命中子切片关联的父切片唯一标识。 */
        String parentChunkUid,
        /** 父切片上下文文本，用于辅助判断证据语境。 */
        String parentContext,
        /** 召回证据 metadata 中的主题标签摘要。 */
        List<String> themeCodes,
        /** 召回证据 metadata 中的行业标签摘要。 */
        List<String> industryCodes,
        /** 召回证据 metadata 中的公司名称摘要。 */
        List<String> companyNames,
        /** 召回证据 metadata 中的股票代码摘要。 */
        List<String> tickers,
        /** 是否仅作为低相关或主题未覆盖诊断候选，而不是推荐证据。 */
        boolean diagnosticOnly
) {
    /**
     * @Description: 兼容旧调用方的Top结果构造器。
     * @Logic: 旧链路只提供分数、标题、切片文本和来源时，新增父子切片定位字段使用空字符串兜底。
     * @Param: score 召回分数；title 研报标题；chunkText 命中子切片文本；source 来源标识。
     * @Return: 无（record构造器）。
     */
    public TopResultRespDTO(Double score, String title, String chunkText, String source) {
        this(score, title, chunkText, source, "", "", "", "", List.of(), List.of(), List.of(), List.of(), false);
    }

    /**
     * @Description: 兼容父子切片定位字段但不携带标签 metadata 的构造器。
     * @Logic: 标签摘要使用空集合，diagnosticOnly 默认 false，保证旧调用方无需感知新增字段。
     * @Param: score 召回分数；title 研报标题；chunkText 子切片文本；source 来源；sectionPath 章节；chunkUid 子切片ID；parentChunkUid 父切片ID；parentContext 父上下文。
     * @Return: 无（record构造器）。
     */
    public TopResultRespDTO(Double score,
                            String title,
                            String chunkText,
                            String source,
                            String sectionPath,
                            String chunkUid,
                            String parentChunkUid,
                            String parentContext) {
        this(score, title, chunkText, source, sectionPath, chunkUid, parentChunkUid, parentContext,
                List.of(), List.of(), List.of(), List.of(), false);
    }
}
