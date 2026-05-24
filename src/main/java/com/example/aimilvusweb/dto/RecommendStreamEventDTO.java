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
        /** 流式事件所处阶段，例如 retrieving、generating、completed、error。 */
        String stage,
        /** 阶段状态消息或错误消息。 */
        String message,
        /** 模型输出的增量文本片段。 */
        String text,
        /** 当前事件携带的召回Top结果，通常在 evidence 事件中返回。 */
        List<TopResultRespDTO> top5,
        /** 输入意图枚举名称，便于前端展示当前query分类。 */
        String inputIntent,
        /** 推荐输出等级枚举名称，便于前端展示降级状态。 */
        String outputLevel,
        /** 输出降级原因列表，便于前端展示证据不足或污染原因。 */
        List<String> degradationReasons,
        /** 证据质量详情，便于前端同步展示召回质量。 */
        RecommendRespDTO.EvidenceQualityRespDTO evidenceQuality
) {
    /**
     * @Description: 兼容旧流式事件调用方的构造器。
     * @Logic: 旧链路只提供阶段、消息、文本和Top5时，防护元数据使用空值兜底。
     * @Param: stage 阶段标识；message 阶段消息；text 模型增量文本；top5 检索证据。
     * @Return: 无（record构造器）。
     */
    public RecommendStreamEventDTO(String stage,
                                   String message,
                                   String text,
                                   List<TopResultRespDTO> top5) {
        this(stage, message, text, top5, "", "", List.of(), null);
    }
}
