package com.example.aimilvusweb.evaluation.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 评测标准证据实体，保存可复现的 reference context 文本和定位快照。
 * @Logic: 从业务 chunk 或人工文本生成，判卷时以 referenceText 快照为准，不依赖当前业务 chunk 的最新内容。
 * @Param: 无。
 * @Return: 评测标准证据对象。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Getter
@Setter
public class EvaluationReferenceContext {

    /** 标准证据主键。 */
    private Long id;
    /** 所属评测语料集主键。 */
    private Long corpusId;
    /** 来源业务报告主键，可为空以支持人工证据。 */
    private Long reportId;
    /** 来源 CHILD chunk 唯一标识。 */
    private String chunkUid;
    /** 来源 PARENT chunk 唯一标识。 */
    private String parentChunkUid;
    /** 标准证据类型，例如 CHILD、PARENT、PAGE 或 MANUAL。 */
    private String contextType;
    /** 证据章节路径快照。 */
    private String sectionPath;
    /** 证据起始页码快照。 */
    private Integer pageStart;
    /** 证据结束页码快照。 */
    private Integer pageEnd;
    /** 判卷使用的标准证据文本快照。 */
    private String referenceText;
    /** 主题标签快照，使用 JSON 或分隔文本保存。 */
    private String themeCodes;
    /** 行业标签快照，使用 JSON 或分隔文本保存。 */
    private String industryCodes;
    /** 公司名称快照，使用 JSON 或分隔文本保存。 */
    private String companyNames;
    /** 股票代码快照，使用 JSON 或分隔文本保存。 */
    private String tickers;
    /** 记录创建时间。 */
    private Instant createdAt;
}
