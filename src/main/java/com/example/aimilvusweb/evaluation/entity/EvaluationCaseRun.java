package com.example.aimilvusweb.evaluation.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: Eval case 单次执行结果实体，保存实际意图、输出、证据质量和自动判分。
 * @Logic: 推荐链路返回后写入响应文本和质量字段，自动判分字段与人工复核字段互不覆盖。
 * @Param: 无。
 * @Return: Eval case 执行结果对象。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Getter
@Setter
public class EvaluationCaseRun {

    /** Case run 主键。 */
    private Long id;
    /** 所属 eval_run 表主键。 */
    private Long evalRunId;
    /** 所属 eval_case 表主键。 */
    private Long casePkId;
    /** 本次执行使用的 query 快照。 */
    private String queryText;
    /** 实际输入意图。 */
    private String actualIntent;
    /** 实际结构化锚点 JSON 或分隔文本。 */
    private String actualAnchors;
    /** 实际输出等级。 */
    private String actualOutputLevel;
    /** 实际降级原因 JSON 或分隔文本。 */
    private String actualDegradationReasons;
    /** Ragas 和人工复核使用的完整响应文本。 */
    private String responseText;
    /** 推荐响应中的分析正文。 */
    private String analysis;
    /** 推荐响应中的推荐或降级结论。 */
    private String recommendation;
    /** 推荐响应中的风险 JSON 或分隔文本。 */
    private String risks;
    /** 推荐响应中的引用 JSON 或分隔文本。 */
    private String citations;
    /** 证据质量 JSON 快照。 */
    private String evidenceQuality;
    /** 实际意图是否匹配期望意图。 */
    private Boolean intentMatch;
    /** 实际锚点是否覆盖标准锚点。 */
    private Boolean anchorMatch;
    /** 实际输出等级是否匹配期望输出等级。 */
    private Boolean outputLevelMatch;
    /** 实际降级原因是否覆盖期望降级原因。 */
    private Boolean degradationReasonMatch;
    /** FINAL 检索结果是否命中标准证据。 */
    private Boolean referenceContextHit;
    /** 检索结果是否命中禁止上下文。 */
    private Boolean forbiddenContextHit;
    /** 响应文本是否命中禁止结论。 */
    private Boolean forbiddenClaimHit;
    /** 证据质量字段是否满足 case 预期。 */
    private Boolean evidenceQualityMatch;
    /** 自动判分是否建议人工复核。 */
    private Boolean needsManualReview;
    /** 自动判分详情 JSON 快照。 */
    private String autoScores;
    /** 人工复核状态，例如 UNREVIEWED 或 REVIEWED。 */
    private String manualReviewStatus;
    /** 执行状态，例如 RUNNING、SUCCEEDED 或 FAILED。 */
    private String status;
    /** Case 级错误摘要。 */
    private String errorMessage;
    /** Case 执行开始时间。 */
    private Instant startedAt;
    /** Case 执行结束时间。 */
    private Instant finishedAt;
    /** 记录创建时间。 */
    private Instant createdAt;
}
