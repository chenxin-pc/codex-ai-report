package com.example.aimilvusweb.dto;

import java.util.List;

/**
 * @Description: 同步推荐接口响应，承载检索证据、推荐正文和投研防护元数据。
 * @Logic: 原有字段保持兼容，新字段用于暴露输入意图、输出等级、降级原因和证据质量。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public record RecommendRespDTO(
        /** 用户原始推荐问题。 */
        String query,
        /** 召回Top结果列表，供前端展示证据来源。 */
        List<TopResultRespDTO> top5,
        /** 模型生成或降级生成的分析正文。 */
        String analysis,
        /** 模型生成或护栏替换后的推荐结论。 */
        String recommendation,
        /** 风险提示列表，降级时可承载降级原因。 */
        List<String> risks,
        /** 引用证据列表，对应模型使用的召回片段。 */
        List<String> citations,
        /** 输入意图枚举名称，标识直接分析、主题分析或不可分析。 */
        String inputIntent,
        /** 输入意图判定置信度。 */
        Double inputConfidence,
        /** 推荐输出等级枚举名称，标识当前允许的输出强度。 */
        String outputLevel,
        /** 输出降级原因列表。 */
        List<String> degradationReasons,
        /** 证据质量详情，描述召回结果是否通过输出前校验。 */
        EvidenceQualityRespDTO evidenceQuality
) {
    /**
     * @Description: 兼容旧调用方的推荐响应构造器。
     * @Logic: 旧链路不提供防护元数据时，使用空意图、空等级、空降级原因和空证据质量兜底。
     * @Param: query 用户问题；top5 检索Top5；analysis 分析正文；recommendation 推荐正文；risks 风险列表；citations 引用列表。
     * @Return: 无（record构造器）。
     */
    public RecommendRespDTO(String query,
                            List<TopResultRespDTO> top5,
                            String analysis,
                            String recommendation,
                            List<String> risks,
                            List<String> citations) {
        this(query, top5, analysis, recommendation, risks, citations, "", null, "", List.of(), null);
    }

    /**
     * @Description: 证据质量响应对象，描述召回证据是否足以支撑当前投研输出。
     * @Logic: 输出前降级服务填充这些布尔指标和问题码，前端与Prompt据此展示或约束回答强度。
     * @Param: evidencePresent 是否存在证据；queryRelevant 是否与问题相关；entityConsistent 标的是否一致；dataConsistent 数据是否一致；citationComplete 引用是否完整；issues 质量问题码。
     */
    public record EvidenceQualityRespDTO(
            /** 是否存在可用证据文本。 */
            boolean evidencePresent,
            /** 召回证据是否与用户query具备基本相关性。 */
            boolean queryRelevant,
            /** 召回证据中的公司、代码、交易所或行业主体是否一致。 */
            boolean entityConsistent,
            /** 股价、市值、估值等数据是否能归属于一致主体。 */
            boolean dataConsistent,
            /** 证据是否具备完整引用和主体锚定。 */
            boolean citationComplete,
            /** 证据质量问题码列表。 */
            List<String> issues
    ) {
    }
}
