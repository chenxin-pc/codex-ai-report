package com.example.aimilvusweb.evaluation.dto;

/**
 * @Description: Eval case 自动判分 DTO，表达项目内指标是否匹配标准和是否需要人工复核。
 * @Logic: 运行服务计算后写回 eval_case_run，并在脚本导出时作为 metadata 输出。
 * @Param: intentMatch 意图是否匹配；anchorMatch 锚点是否匹配；outputLevelMatch 输出等级是否匹配；degradationReasonMatch 降级原因是否匹配；referenceContextHit 标准证据是否命中；forbiddenContextHit 是否命中禁止证据；forbiddenClaimHit 是否命中禁止结论；evidenceQualityMatch 证据质量是否匹配；needsManualReview 是否需要人工复核；detailJson 判分详情。
 * @Return: 自动判分结果视图。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
public record EvaluationAutoScoreRespDTO(
        /** 实际意图是否匹配 expectedIntent。 */
        Boolean intentMatch,
        /** 实际锚点是否覆盖 required 标准锚点。 */
        Boolean anchorMatch,
        /** 实际输出等级是否匹配 expectedOutputLevel。 */
        Boolean outputLevelMatch,
        /** 实际降级原因是否覆盖 expectedDegradationReasons。 */
        Boolean degradationReasonMatch,
        /** FINAL retrieved context 是否命中标准证据。 */
        Boolean referenceContextHit,
        /** retrieved context 是否命中禁止上下文。 */
        Boolean forbiddenContextHit,
        /** responseText 是否命中 forbiddenClaims 或 forbiddenTerms。 */
        Boolean forbiddenClaimHit,
        /** evidenceQuality 是否满足 case 预期。 */
        Boolean evidenceQualityMatch,
        /** 是否建议人工复核。 */
        Boolean needsManualReview,
        /** 自动判分详情 JSON。 */
        String detailJson
) {
}
