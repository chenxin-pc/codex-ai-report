package com.example.aimilvusweb.evaluation.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 评测语料集实体，保存一批可复现研报评测样本的版本、模型和词典快照。
 * @Logic: 作为 eval case、reference context 和 eval run 的根对象，业务报告只通过快照弱关联到该语料集。
 * @Param: 无。
 * @Return: 评测语料集持久化对象。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Getter
@Setter
public class EvaluationCorpus {

    /** 评测语料集主键。 */
    private Long id;
    /** 外部稳定语料编码，例如 core-v1。 */
    private String corpusCode;
    /** 语料集名称，供脚本和报告展示。 */
    private String name;
    /** 语料集说明，记录覆盖主题、样本来源和适用范围。 */
    private String description;
    /** 语料版本，支持同一 corpusCode 下的多轮冻结版本。 */
    private String corpusVersion;
    /** 创建语料时使用的词典版本快照。 */
    private String dictionaryVersion;
    /** 创建语料时使用或期望使用的 embedding 模型。 */
    private String embeddingModel;
    /** 语料状态，常用 ACTIVE 或 DISABLED。 */
    private String status;
    /** 记录创建时间。 */
    private Instant createdAt;
    /** 记录最后更新时间。 */
    private Instant updatedAt;
}
