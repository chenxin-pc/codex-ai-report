package com.example.aimilvusweb.evaluation.entity;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @Description: Eval case 检索上下文实体，保存 FINAL 或诊断候选的文本、分数和 metadata 快照。
 * @Logic: 评测运行将推荐 Top 结果映射为 retrieved context，后续自动判分和 Ragas 导出读取该表。
 * @Param: 无。
 * @Return: Eval case 检索上下文对象。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Getter
@Setter
public class EvaluationRetrievedContext {

    /** 检索上下文主键。 */
    private Long id;
    /** 所属 case run 主键。 */
    private Long caseRunId;
    /** 检索阶段，至少支持 FINAL，预留 INITIAL、FILTERED、DIAGNOSTIC。 */
    private String stage;
    /** 当前阶段内的排序名次。 */
    private Integer rankNo;
    /** 来源业务报告主键。 */
    private Long reportId;
    /** 命中 CHILD chunk 唯一标识。 */
    private String chunkUid;
    /** 命中 PARENT chunk 唯一标识。 */
    private String parentChunkUid;
    /** 命中证据章节路径。 */
    private String sectionPath;
    /** 原始检索分数。 */
    private BigDecimal score;
    /** 统一方向后的相关性分数。 */
    private BigDecimal relevanceScore;
    /** 生成上下文来源类型。 */
    private String contextType;
    /** 是否仅用于诊断而不是推荐证据。 */
    private Boolean diagnosticOnly;
    /** 证据上下文是否被截断。 */
    private Boolean truncated;
    /** PARENT 聚合命中的子 chunk 数量。 */
    private Integer hitCount;
    /** 检索上下文文本快照。 */
    private String retrievedText;
    /** 检索 metadata JSON 快照。 */
    private String metadataJson;
    /** 人工复核相关性等级。 */
    private Integer manualRelevanceLevel;
    /** 人工复核问题备注。 */
    private String manualIssueNotes;
    /** 人工复核建议处理方式。 */
    private String manualSuggestedAction;
    /** 记录创建时间。 */
    private Instant createdAt;
}
