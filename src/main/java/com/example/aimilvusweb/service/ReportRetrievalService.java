package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
/**
 * @Description: ReportRetrievalService类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class ReportRetrievalService {

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ReportChunkMapper reportChunkMapper;
    private final ReportQualityProperties reportQualityProperties;

    /**
     * @Description: 初始化ReportRetrievalService依赖与运行所需组件。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public ReportRetrievalService(ObjectProvider<VectorStore> vectorStoreProvider,
                                  ReportChunkMapper reportChunkMapper,
                                  ReportQualityProperties reportQualityProperties) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.reportChunkMapper = reportChunkMapper;
        this.reportQualityProperties = reportQualityProperties;
    }

    /**
     * @Description: 检索候选数据并返回排序结果。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public List<RetrievedChunk> retrieve(String query) {
        VectorStore vectorStore = requireVectorStore();
        int initialTopK = Math.max(reportQualityProperties.getRetrieval().getInitialTopK(), 1);
        int finalTopK = Math.max(reportQualityProperties.getRetrieval().getFinalTopK(), 1);
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(initialTopK).build());
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> candidates = new ArrayList<>();
        for (Document doc : docs) {
            Double score = resolveScore(doc);
            if (shouldFilterByScore(score)) {
                continue;
            }
            String chunkText = doc.getText() == null ? "" : doc.getText();
            candidates.add(new RetrievedChunk(doc, score, chunkText, expandContext(doc, chunkText)));
        }

        List<RetrievedChunk> deduplicated = deduplicateByChunkUid(candidates);
        if (reportQualityProperties.getRetrieval().isRerankEnabled()) {
            deduplicated = rerankByQueryOverlap(query, deduplicated);
        }
        return deduplicated.stream()
                .limit(finalTopK)
                .toList();
    }

    /**
     * @Description: 执行requireVectorStore相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private VectorStore requireVectorStore() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new IllegalStateException("VectorStore is not configured. Set spring.ai.vectorstore.type=milvus and Milvus properties.");
        }
        return vectorStore;
    }

    /**
     * @Description: 执行shouldFilterByScore相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private boolean shouldFilterByScore(Double score) {
        return score != null
                && reportQualityProperties.getRetrieval().getMinSimilarityScore() > 0D
                && score < reportQualityProperties.getRetrieval().getMinSimilarityScore();
    }

    /**
     * @Description: 执行deduplicateByChunkUid相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private List<RetrievedChunk> deduplicateByChunkUid(List<RetrievedChunk> candidates) {
        Map<String, RetrievedChunk> deduplicated = new LinkedHashMap<>();
        for (RetrievedChunk candidate : candidates) {
            String chunkUid = String.valueOf(candidate.document().getMetadata().getOrDefault("chunkUid", ""));
            String key = chunkUid.isBlank() ? "doc-" + deduplicated.size() : chunkUid;
            deduplicated.putIfAbsent(key, candidate);
        }
        return new ArrayList<>(deduplicated.values());
    }

    /**
     * @Description: 执行rerankByQueryOverlap相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private List<RetrievedChunk> rerankByQueryOverlap(String query, List<RetrievedChunk> candidates) {
        String normalizedQuery = normalizeForOverlap(query);
        return candidates.stream()
                .sorted(Comparator.comparingInt((RetrievedChunk candidate) ->
                        overlapScore(normalizedQuery, normalizeForOverlap(candidate.chunkText()))).reversed())
                .toList();
    }

    /**
     * @Description: 执行overlapScore相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private int overlapScore(String query, String text) {
        if (query.isBlank() || text.isBlank()) {
            return 0;
        }
        int score = 0;
        for (int i = 0; i < query.length(); i++) {
            if (text.indexOf(query.charAt(i)) >= 0) {
                score++;
            }
        }
        return score;
    }

    /**
     * @Description: 对输入数据进行规范化处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String normalizeForOverlap(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * @Description: 根据上下文解析并确定最终值。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private Double resolveScore(Document doc) {
        Object score = doc.getMetadata().get("score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        Object distance = doc.getMetadata().get("distance");
        if (distance instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    /**
     * @Description: 执行expandContext相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String expandContext(Document doc, String fallbackText) {
        String parentChunkUid = String.valueOf(doc.getMetadata().getOrDefault("parentChunkUid", ""));
        if (parentChunkUid.isBlank()) {
            return fallbackText;
        }
        ReportChunk parentChunk = reportChunkMapper.selectByChunkUid(parentChunkUid);
        if (parentChunk == null || parentChunk.getChunkText() == null || parentChunk.getChunkText().isBlank()) {
            return fallbackText;
        }
        return limitTokens(parentChunk.getChunkText(), reportQualityProperties.getRetrieval().getMaxParentContextTokens());
    }

    /**
     * @Description: 按阈值限制输出内容范围。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String limitTokens(String text, int maxTokens) {
        if (SemanticChunkUtils.estimateTokens(text) <= maxTokens) {
            return text;
        }
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder limited = new StringBuilder();
        int tokens = 0;
        for (String paragraph : paragraphs) {
            int paragraphTokens = SemanticChunkUtils.estimateTokens(paragraph);
            if (tokens > 0 && tokens + paragraphTokens > maxTokens) {
                break;
            }
            if (!limited.isEmpty()) {
                limited.append("\n\n");
            }
            limited.append(paragraph.trim());
            tokens += paragraphTokens;
        }
        return limited.toString();
    }

    public record RetrievedChunk(
            Document document,
            Double score,
            String chunkText,
            String evidenceText
    ) {
    }
}
