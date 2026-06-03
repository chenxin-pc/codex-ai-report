package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.service.retrieval.EvidenceContextType;
import com.example.aimilvusweb.service.retrieval.RetrievedChild;
import com.example.aimilvusweb.service.retrieval.RetrievedChunk;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * @Description: 逐 CHILD 扩展 PARENT 上下文的证据构建策略。
 * @Logic: 用于 parentAggregationEnabled=false 场景，逐条候选按 parentChunkUid 回查父切片并按 token 预算截断，缺失时回退 CHILD 文本。
 * @Param: 无。
 * @Return: 无（策略组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class ChildExpansionEvidenceContextStrategy implements EvidenceContextStrategy {

    /** 研报切片 Mapper，用于根据 parentChunkUid 查询 PARENT 切片。 */
    private final ReportChunkMapper reportChunkMapper;
    /** 研报质量配置，用于读取父上下文最大 token 数。 */
    private final ReportQualityProperties reportQualityProperties;

    /**
     * @Description: 初始化 CHILD 扩展证据策略。
     * @Logic: 保存切片 Mapper 和质量配置，构建证据时用于回查 PARENT 与执行 token 截断。
     * @Param: reportChunkMapper 切片 Mapper；reportQualityProperties 研报质量配置。
     * @Return: 无（仅初始化策略依赖）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public ChildExpansionEvidenceContextStrategy(ReportChunkMapper reportChunkMapper,
                                                 ReportQualityProperties reportQualityProperties) {
        this.reportChunkMapper = reportChunkMapper;
        this.reportQualityProperties = reportQualityProperties;
    }

    /**
     * @Description: 将候选逐条扩展为最终证据。
     * @Logic: 先按 finalTopK 截断候选数量，再为每个 CHILD 回查父上下文；父上下文不可用时保留 CHILD_FALLBACK。
     * @Param: candidates 去重和可选重排后的 CHILD 候选；finalTopK 最终返回数量。
     * @Return: 带 evidenceText 的最终证据列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    @Override
    public List<RetrievedChunk> build(List<RetrievedChunk> candidates, int finalTopK) {
        return candidates.stream()
                .limit(finalTopK)
                .map(this::expandRetrievedChunk)
                .toList();
    }

    /**
     * @Description: 扩展单个 CHILD 候选的 PARENT 上下文。
     * @Logic: 有 PARENT 文本时返回截断后的父上下文；否则使用 CHILD 文本作为 evidenceText。
     * @Param: candidate CHILD 候选。
     * @Return: 扩展后的召回证据。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private RetrievedChunk expandRetrievedChunk(RetrievedChunk candidate) {
        String evidenceText = expandContext(candidate.document(), candidate.chunkText());
        EvidenceContextType contextType = evidenceText.equals(candidate.chunkText()) ? EvidenceContextType.CHILD_FALLBACK : EvidenceContextType.TRUNCATED_PARENT;
        return new RetrievedChunk(candidate.document(), candidate.score(), candidate.chunkText(), evidenceText, contextType,
                List.of(new RetrievedChild(candidate.document(), candidate.score(), candidate.chunkText(), 0)),
                1, candidate.score() == null ? 0D : candidate.score(), candidate.score() == null ? 0D : candidate.score(),
                SemanticChunkUtils.estimateTokens(evidenceText) > reportQualityProperties.getRetrieval().getMaxParentContextTokens(), false);
    }

    /**
     * @Description: 根据 parentChunkUid 回查并截断父上下文。
     * @Logic: 缺失 parentChunkUid、PARENT 不存在或父文本为空时返回 fallbackText；否则按配置 token 上限截断父文本。
     * @Param: document 候选 Milvus 文档；fallbackText 命中 CHILD 文本。
     * @Return: PARENT 上下文或 CHILD 兜底文本。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private String expandContext(Document document, String fallbackText) {
        String parentChunkUid = RetrievalMetadataUtils.metadataText(document, "parentChunkUid");
        if (parentChunkUid.isBlank()) {
            return fallbackText;
        }
        ReportChunk parentChunk = reportChunkMapper.selectByChunkUid(parentChunkUid);
        if (parentChunk == null || parentChunk.getChunkText() == null || parentChunk.getChunkText().isBlank()) {
            return fallbackText;
        }
        return RetrievalTextLimiter.limitTokens(parentChunk.getChunkText(), reportQualityProperties.getRetrieval().getMaxParentContextTokens());
    }
}
