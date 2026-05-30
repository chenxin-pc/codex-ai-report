package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.ReportRetrievalService.RetrievedChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 召回候选稳定去重组件。
 * @Logic: 优先使用 chunkUid 或 Document id 去重，再使用标题、来源、章节和正文构造内容 key 兜住重复入库或 metadata 缺失场景。
 * @Param: 无。
 * @Return: 无（无状态去重组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class RetrievedChunkDeduplicator {

    /**
     * @Description: 对召回候选执行稳定去重。
     * @Logic: 保留第一次出现的候选，后续 identity key 或 content key 重复的候选会被跳过，保证召回顺序稳定。
     * @Param: candidates 分数过滤后的召回候选。
     * @Return: 去重后的候选列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public List<RetrievedChunk> deduplicate(List<RetrievedChunk> candidates) {
        Map<String, RetrievedChunk> identityKeys = new LinkedHashMap<>();
        Map<String, RetrievedChunk> contentKeys = new LinkedHashMap<>();
        List<RetrievedChunk> deduplicated = new ArrayList<>();
        for (RetrievedChunk candidate : candidates) {
            String identityKey = identityDedupKey(candidate);
            String contentKey = contentDedupKey(candidate);
            if ((!identityKey.isBlank() && identityKeys.containsKey(identityKey))
                    || (!contentKey.isBlank() && contentKeys.containsKey(contentKey))) {
                continue;
            }
            deduplicated.add(candidate);
            if (!identityKey.isBlank()) {
                identityKeys.put(identityKey, candidate);
            }
            if (!contentKey.isBlank()) {
                contentKeys.put(contentKey, candidate);
            }
        }
        return deduplicated;
    }

    /**
     * @Description: 构造候选身份去重键。
     * @Logic: 优先使用业务 chunkUid；缺少 chunkUid 时回退到 Spring AI Document id。
     * @Param: candidate 召回候选。
     * @Return: 身份去重 key；无可用身份时返回空字符串。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public String identityDedupKey(RetrievedChunk candidate) {
        String chunkUid = RetrievalMetadataUtils.metadataText(candidate.document(), "chunkUid");
        if (!chunkUid.isBlank()) {
            return "chunkUid:" + chunkUid;
        }
        String documentId = candidate.document().getId() == null ? "" : candidate.document().getId().trim();
        if (!documentId.isBlank()) {
            return "documentId:" + documentId;
        }
        return "";
    }

    /**
     * @Description: 构造候选内容去重键。
     * @Logic: 标题、来源、章节和正文都一致时视作重复展示项；正文为空时不做内容去重。
     * @Param: candidate 召回候选。
     * @Return: 内容去重 key；正文为空时返回空字符串。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private String contentDedupKey(RetrievedChunk candidate) {
        String chunkText = RetrievalMetadataUtils.normalizeForDedup(candidate.chunkText());
        if (chunkText.isBlank()) {
            return "";
        }
        return "content:"
                + RetrievalMetadataUtils.normalizeForDedup(RetrievalMetadataUtils.metadataText(candidate.document(), "title")) + "|"
                + RetrievalMetadataUtils.normalizeForDedup(RetrievalMetadataUtils.metadataText(candidate.document(), "source")) + "|"
                + RetrievalMetadataUtils.normalizeForDedup(RetrievalMetadataUtils.metadataText(candidate.document(), "sectionPath")) + "|"
                + chunkText;
    }
}
