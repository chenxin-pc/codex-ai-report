package com.example.aimilvusweb.evaluation.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 研报 RAG 评测 case 实体，保存 query、期望意图、期望输出等级和判卷规则。
 * @Logic: 每条 case 挂在一个 corpus 下，后续运行时用其标准字段与实际推荐结果计算自动判分。
 * @Param: 无。
 * @Return: 评测 case 持久化对象。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Getter
@Setter
public class EvaluationCase {

    /** 评测 case 主键。 */
    private Long id;
    /** 所属评测语料集主键。 */
    private Long corpusId;
    /** 外部稳定 case 编码。 */
    private String caseId;
    /** 用户原始或标准化评测 query。 */
    private String queryText;
    /** case 类型，例如 THEME_RESEARCH、NO_EVIDENCE 或 UNANALYZABLE。 */
    private String caseType;
    /** case 难度标签，例如 easy、medium 或 hard。 */
    private String difficulty;
    /** 期望输入意图枚举名。 */
    private String expectedIntent;
    /** 期望输出等级枚举名。 */
    private String expectedOutputLevel;
    /** 期望降级原因 JSON 或分隔文本快照。 */
    private String expectedDegradationReasons;
    /** 参考答案文本，用于 Ragas reference 字段。 */
    private String referenceAnswer;
    /** 必须覆盖的标准 claims，使用 JSON 或分隔文本保存。 */
    private String requiredClaims;
    /** 禁止生成的 claims，使用 JSON 或分隔文本保存。 */
    private String forbiddenClaims;
    /** 禁止出现的词或短语，使用 JSON 或分隔文本保存。 */
    private String forbiddenTerms;
    /** 是否参与默认评测运行。 */
    private Boolean enabled;
    /** 记录创建时间。 */
    private Instant createdAt;
    /** 记录最后更新时间。 */
    private Instant updatedAt;
}
