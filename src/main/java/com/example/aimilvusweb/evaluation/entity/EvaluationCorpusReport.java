package com.example.aimilvusweb.evaluation.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * @Description: 评测语料报告快照实体，记录业务报告加入 corpus 时的判卷相关元数据。
 * @Logic: 通过 reportId 弱关联业务报告，同时冗余标题、来源、机构、日期和指纹，避免业务表变化影响历史评测。
 * @Param: 无。
 * @Return: 评测语料报告快照对象。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Getter
@Setter
public class EvaluationCorpusReport {

    /** 语料报告快照主键。 */
    private Long id;
    /** 所属评测语料集主键。 */
    private Long corpusId;
    /** 被快照的业务研报主键。 */
    private Long reportId;
    /** PDF 或导入文件指纹，用于识别语料是否变动。 */
    private String reportFingerprint;
    /** 加入语料时的报告标题快照。 */
    private String titleSnapshot;
    /** 加入语料时的报告来源快照。 */
    private String sourceSnapshot;
    /** 加入语料时的机构快照。 */
    private String institutionSnapshot;
    /** 加入语料时的发布日期快照。 */
    private LocalDate publishDateSnapshot;
    /** 记录创建时间。 */
    private Instant createdAt;
}
