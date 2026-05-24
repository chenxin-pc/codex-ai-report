package com.example.aimilvusweb.dto;

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
        String parentContext
) {
    /**
     * @Description: 兼容旧调用方的Top结果构造器。
     * @Logic: 旧链路只提供分数、标题、切片文本和来源时，新增父子切片定位字段使用空字符串兜底。
     * @Param: score 召回分数；title 研报标题；chunkText 命中子切片文本；source 来源标识。
     * @Return: 无（record构造器）。
     */
    public TopResultRespDTO(Double score, String title, String chunkText, String source) {
        this(score, title, chunkText, source, "", "", "", "");
    }
}
