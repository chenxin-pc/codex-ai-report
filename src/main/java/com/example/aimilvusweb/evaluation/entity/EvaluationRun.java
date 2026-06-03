package com.example.aimilvusweb.evaluation.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 评测运行实体，记录一次 eval run 的语料、模型、prompt、词典和检索配置快照。
 * @Logic: 每个 case run 都挂在 eval run 下，历史结果通过运行快照解释分数变化。
 * @Param: 无。
 * @Return: 评测运行持久化对象。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Getter
@Setter
public class EvaluationRun {

    /** 评测运行主键。 */
    private Long id;
    /** 外部稳定运行编号。 */
    private String runId;
    /** 所属评测语料集主键。 */
    private Long corpusId;
    /** 应用代码提交快照，无法获取时为 unknown。 */
    private String appCommit;
    /** Prompt 版本快照。 */
    private String promptVersion;
    /** Prompt 内容哈希快照。 */
    private String promptHash;
    /** Embedding 模型快照。 */
    private String embeddingModel;
    /** LLM 模型快照。 */
    private String llmModel;
    /** 检索配置 JSON 快照。 */
    private String retrievalConfig;
    /** 词典版本快照。 */
    private String dictionaryVersion;
    /** 运行状态，例如 RUNNING、SUCCEEDED 或 FAILED。 */
    private String status;
    /** 运行级错误摘要。 */
    private String errorSummary;
    /** 运行开始时间。 */
    private Instant startedAt;
    /** 运行结束时间。 */
    private Instant finishedAt;
    /** 记录创建时间。 */
    private Instant createdAt;
}
