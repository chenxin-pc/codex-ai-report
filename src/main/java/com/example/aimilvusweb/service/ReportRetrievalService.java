package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.service.ResearchQueryAnchorService.QueryAnchors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
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
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class ReportRetrievalService {

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ReportChunkMapper reportChunkMapper;
    private final ReportQualityProperties reportQualityProperties;
    /** query 锚点抽取服务，用于把用户输入转换为 Milvus metadata filter。 */
    private final ResearchQueryAnchorService researchQueryAnchorService;
    /** chunk 标签 Mapper，用于 metadata 不同步时进行 MySQL 主数据诊断。 */
    private final ReportChunkTagMapper reportChunkTagMapper;

    /**
     * @Description: 初始化ReportRetrievalService依赖与运行所需组件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    @Autowired
    public ReportRetrievalService(ObjectProvider<VectorStore> vectorStoreProvider,
                                  ReportChunkMapper reportChunkMapper,
                                  ReportQualityProperties reportQualityProperties,
                                  ResearchQueryAnchorService researchQueryAnchorService,
                                  ReportChunkTagMapper reportChunkTagMapper) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.reportChunkMapper = reportChunkMapper;
        this.reportQualityProperties = reportQualityProperties;
        this.researchQueryAnchorService = researchQueryAnchorService;
        this.reportChunkTagMapper = reportChunkTagMapper;
    }

    /**
     * @Description: 兼容旧测试的检索服务构造器。
     * @Logic: 未注入 query 锚点和标签 Mapper 时退化为纯向量召回，保持旧单元测试可独立构造。
     * @Param: vectorStoreProvider 向量库提供器；reportChunkMapper chunk Mapper；reportQualityProperties 检索配置。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public ReportRetrievalService(ObjectProvider<VectorStore> vectorStoreProvider,
                                  ReportChunkMapper reportChunkMapper,
                                  ReportQualityProperties reportQualityProperties) {
        this(vectorStoreProvider, reportChunkMapper, reportQualityProperties, null, null);
    }

    /**
     * @Description: 检索候选数据并返回排序结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public List<RetrievedChunk> retrieve(String query) {
        VectorStore vectorStore = requireVectorStore();
        int initialTopK = Math.max(reportQualityProperties.getRetrieval().getInitialTopK(), 1);
        int finalTopK = Math.max(reportQualityProperties.getRetrieval().getFinalTopK(), 1);
        QueryAnchors anchors = extractAnchors(query);
        SearchRequest searchRequest = buildSearchRequest(query, initialTopK, anchors);
        List<Document> docs = vectorStore.similaritySearch(searchRequest);
        if (docs == null || docs.isEmpty()) {
            diagnoseMetadataFallback(anchors);
            return List.of();
        }

        List<RetrievedChunk> candidates = new ArrayList<>();
        for (Document doc : docs) {
            Double relevanceScore = resolveRelevanceScore(doc);
            if (shouldFilterByScore(relevanceScore)) {
                continue;
            }
            String chunkText = doc.getText() == null ? "" : doc.getText();
            candidates.add(new RetrievedChunk(doc, relevanceScore, chunkText, expandContext(doc, chunkText)));
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
     * @Description: 从 query 中抽取结构化锚点。
     * @Logic: 锚点服务未注入时返回空锚点，兼容旧测试和局部配置。
     * @Param: query 用户投研问题。
     * @Return: query 锚点对象。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private QueryAnchors extractAnchors(String query) {
        if (researchQueryAnchorService == null) {
            return new QueryAnchors(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        return researchQueryAnchorService.extract(query);
    }

    /**
     * @Description: 构建 Milvus ANN 检索请求。
     * @Logic: 有结构化主题、行业或代码锚点时优先附加 metadata scalar filter；无锚点时保持纯向量召回。
     * @Param: query 用户投研问题；topK 初始召回数量；anchors query 结构化锚点。
     * @Return: SearchRequest 检索请求。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private SearchRequest buildSearchRequest(String query, int topK, QueryAnchors anchors) {
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);
        FilterExpressionBuilder.Op filter = buildMetadataFilter(anchors);
        if (filter != null) {
            builder.filterExpression(filter.build());
        }
        return builder.build();
    }

    /**
     * @Description: 将 query 锚点转换为 metadata filter。
     * @Logic: 当前只使用主题、行业和股票代码等结构化标量字段，不对 chunkText 或 parentText 做 contains 查询。
     * @Param: anchors query 结构化锚点。
     * @Return: Spring AI filter 表达式；无可过滤锚点时返回 null。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private FilterExpressionBuilder.Op buildMetadataFilter(QueryAnchors anchors) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filter = null;
        filter = orFilter(builder, filter, firstEq(builder, "themeCode", anchors.themeCodes()));
        filter = orFilter(builder, filter, firstEq(builder, "industryCode", anchors.industryCodes()));
        filter = orFilter(builder, filter, firstEq(builder, "ticker", anchors.tickers()));
        return filter;
    }

    /**
     * @Description: 使用列表首个值构造等值过滤表达式。
     * @Logic: Milvus metadata 使用 primary 单值字段承载 scalar filter，列表字段仅用于解释和展示。
     * @Param: builder filter 构建器；field metadata 字段名；values 候选值列表。
     * @Return: 等值过滤表达式；无值时返回 null。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private FilterExpressionBuilder.Op firstEq(FilterExpressionBuilder builder, String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return builder.eq(field, values.get(0));
    }

    /**
     * @Description: 合并两个 metadata filter 表达式。
     * @Logic: 多个结构化锚点使用 OR 连接，避免过滤过窄导致完全漏召回。
     * @Param: builder filter 构建器；left 左表达式；right 右表达式。
     * @Return: 合并后的表达式。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private FilterExpressionBuilder.Op orFilter(FilterExpressionBuilder builder,
                                                FilterExpressionBuilder.Op left,
                                                FilterExpressionBuilder.Op right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return builder.or(left, right);
    }

    /**
     * @Description: 在 Milvus metadata filter 无结果时执行 MySQL 标签主数据诊断。
     * @Logic: 仅判断主数据是否存在相关主题标签，不把 MySQL 标签结果直接包装为推荐证据。
     * @Param: anchors query 结构化锚点。
     * @Return: 无（当前用于诊断扩展点，后续可接入日志或响应诊断字段）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private void diagnoseMetadataFallback(QueryAnchors anchors) {
        if (reportChunkTagMapper == null || anchors.themeCodes().isEmpty()) {
            return;
        }
        reportChunkTagMapper.countByTagCodes("THEME", anchors.themeCodes());
    }

    /**
     * @Description: 执行requireVectorStore相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String normalizeForOverlap(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * @Description: 根据上下文解析并确定最终值。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private Double resolveRelevanceScore(Document doc) {
        Object score = doc.getMetadata().get("score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        Object distance = doc.getMetadata().get("distance");
        if (distance instanceof Number number) {
            return 1.0D / (1.0D + Math.max(0D, number.doubleValue()));
        }
        if (doc.getScore() != null) {
            return doc.getScore();
        }
        return null;
    }

    /**
     * @Description: 执行expandContext相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
