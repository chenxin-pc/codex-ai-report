package com.example.aimilvusweb.evaluation.dto;

/**
 * @Description: Eval run 快照请求 DTO，承载一次评测运行的版本、模型和配置快照。
 * @Logic: 运行服务创建 eval_run 时读取该对象，缺失的非核心字段由服务补为 unknown 或空值。
 * @Param: runId 运行编号；corpusId 语料主键；appCommit 应用提交；promptVersion prompt版本；promptHash prompt哈希；embeddingModel 向量模型；llmModel 生成模型；retrievalConfig 检索配置；dictionaryVersion 词典版本。
 * @Return: Eval run 创建所需快照输入。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
public record EvaluationRunSnapshotReqDTO(
        /** 外部稳定运行编号；为空时由服务生成。 */
        String runId,
        /** 评测语料集主键。 */
        Long corpusId,
        /** 应用提交快照。 */
        String appCommit,
        /** Prompt 版本快照。 */
        String promptVersion,
        /** Prompt 内容哈希快照。 */
        String promptHash,
        /** Embedding 模型快照。 */
        String embeddingModel,
        /** LLM 模型快照。 */
        String llmModel,
        /** 检索配置 JSON 快照。 */
        String retrievalConfig,
        /** 词典版本快照。 */
        String dictionaryVersion
) {
}
