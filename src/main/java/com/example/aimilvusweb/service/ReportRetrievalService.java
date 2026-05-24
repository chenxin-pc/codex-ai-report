package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ReportDocumentTagMapper;
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
import java.util.Locale;
import java.util.Map;

/**
 * @Description: 研报召回服务，负责把用户 query 转为 Milvus ANN + metadata scalar filter 查询并返回可展示证据。
 * @Logic: 先抽取 query 结构化锚点构造过滤条件，再执行向量召回、分数过滤、父上下文扩展、去重、可选重排和 TopK 截断。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 召回结果列表，每条结果包含命中子切片文本、父切片上下文和召回分数。
 * @author: cx
 * @Date: 2026-05-24 18:40:00
 */
@Service
public class ReportRetrievalService {

    /** 向量库提供器，允许测试或未配置 Milvus 时按需检测是否可用。 */
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    /** 研报切片 Mapper，用于根据 parentChunkUid 回查父切片上下文。 */
    private final ReportChunkMapper reportChunkMapper;
    /** 研报质量配置，提供召回 TopK、相似度阈值、父上下文长度和重排开关。 */
    private final ReportQualityProperties reportQualityProperties;
    /** query 锚点抽取服务，用于把用户输入转换为 Milvus metadata filter。 */
    private final ResearchQueryAnchorService researchQueryAnchorService;
    /** chunk 标签 Mapper，用于 metadata 不同步时进行 MySQL 主数据诊断。 */
    private final ReportChunkTagMapper reportChunkTagMapper;
    /** 报告级标签 Mapper，用于 metadata 不同步时进行父标签主数据诊断。 */
    private final ReportDocumentTagMapper reportDocumentTagMapper;

    /**
     * @Description: 初始化ReportRetrievalService依赖与运行所需组件。
     * @Logic: 保存向量库、切片查询、质量配置、锚点抽取和标签诊断依赖，供召回主流程复用。
     * @Param: vectorStoreProvider 向量库提供器；reportChunkMapper 切片 Mapper；reportQualityProperties 质量配置；researchQueryAnchorService 锚点抽取服务；reportChunkTagMapper 标签 Mapper。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    @Autowired
    public ReportRetrievalService(ObjectProvider<VectorStore> vectorStoreProvider,
                                  ReportChunkMapper reportChunkMapper,
                                  ReportQualityProperties reportQualityProperties,
                                  ResearchQueryAnchorService researchQueryAnchorService,
                                  ReportChunkTagMapper reportChunkTagMapper,
                                  ReportDocumentTagMapper reportDocumentTagMapper) {
        // 保存向量库提供器，实际查询时再判断 Milvus 是否已配置。
        this.vectorStoreProvider = vectorStoreProvider;
        // 保存切片 Mapper，后续用于根据 parentChunkUid 取父切片文本。
        this.reportChunkMapper = reportChunkMapper;
        // 保存质量配置，召回数量、分数阈值和上下文长度都从这里读取。
        this.reportQualityProperties = reportQualityProperties;
        // 保存 query 锚点抽取服务，构造 metadata filter 时使用。
        this.researchQueryAnchorService = researchQueryAnchorService;
        // 保存标签 Mapper，在 Milvus metadata 未命中时用于主数据诊断。
        this.reportChunkTagMapper = reportChunkTagMapper;
        // 保存报告级标签 Mapper，在父标签 metadata 未命中时用于主数据诊断。
        this.reportDocumentTagMapper = reportDocumentTagMapper;
    }

    /**
     * @Description: 兼容旧测试的检索服务构造器。
     * @Logic: 未注入报告级标签 Mapper 时仅保留 chunk 标签诊断能力。
     * @Param: vectorStoreProvider 向量库提供器；reportChunkMapper chunk Mapper；reportQualityProperties 检索配置；researchQueryAnchorService query 锚点服务；reportChunkTagMapper chunk 标签 Mapper。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public ReportRetrievalService(ObjectProvider<VectorStore> vectorStoreProvider,
                                  ReportChunkMapper reportChunkMapper,
                                  ReportQualityProperties reportQualityProperties,
                                  ResearchQueryAnchorService researchQueryAnchorService,
                                  ReportChunkTagMapper reportChunkTagMapper) {
        this(vectorStoreProvider, reportChunkMapper, reportQualityProperties, researchQueryAnchorService, reportChunkTagMapper, null);
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
        this(vectorStoreProvider, reportChunkMapper, reportQualityProperties, null, null, null);
    }

    /**
     * @Description: 执行研报召回并返回最终 TopK 证据。
     * @Logic: 获取向量库后读取召回配置，抽取 query 锚点并构造 SearchRequest；Milvus 返回候选后做分数过滤、父上下文扩展、去重、可选重排和 TopK 截断。
     * @Param: query 用户规范化后的投研问题。
     * @Return: 最终召回证据列表；无候选或全部过滤时返回空列表。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    public List<RetrievedChunk> retrieve(String query) {
        // 获取已配置的 VectorStore；未配置时直接抛出明确异常，避免静默返回空证据。
        VectorStore vectorStore = requireVectorStore();
        // 读取初始召回数量，并保证最小值为 1，避免向量库收到非法 topK。
        int initialTopK = Math.max(reportQualityProperties.getRetrieval().getInitialTopK(), 1);
        // 读取最终返回数量，并保证最小值为 1，避免截断逻辑返回异常。
        int finalTopK = Math.max(reportQualityProperties.getRetrieval().getFinalTopK(), 1);
        // 从 query 中抽取主题、行业、公司、代码和章节意图等结构化锚点。
        QueryAnchors anchors = extractAnchors(query);
        // 基于 query、TopK 和锚点构造 Milvus ANN + metadata scalar filter 请求。
        SearchRequest searchRequest = buildSearchRequest(query, initialTopK, anchors);
        // 调用向量库执行相似度检索，返回候选 Document 列表。
        List<Document> docs = vectorStore.similaritySearch(searchRequest);
        // 向量库无结果时触发标签主数据诊断，并返回空证据交给上层降级。
        if (docs == null || docs.isEmpty()) {
            // 诊断 Milvus metadata 未命中是否可能由标签尚未同步导致。
            diagnoseMetadataFallback(anchors);
            // 无 Milvus 证据时不从 MySQL 拼装伪证据，保证推荐输出来源可信。
            return List.of();
        }

        // 初始化候选结果集合，后续逐条写入通过分数过滤的召回 chunk。
        List<RetrievedChunk> candidates = new ArrayList<>();
        // 遍历 Milvus 返回的每个候选 Document。
        for (Document doc : docs) {
            // 从 Document metadata 或 score 字段中解析统一相关性分数。
            Double relevanceScore = resolveRelevanceScore(doc);
            // 分数低于配置阈值时丢弃该候选，避免低相关证据进入模型。
            if (shouldFilterByScore(relevanceScore)) {
                // 跳过当前低分候选，继续处理下一条召回结果。
                continue;
            }
            // 使用 Document 正文作为命中子切片文本；空正文统一转为空字符串。
            String chunkText = doc.getText() == null ? "" : doc.getText();
            // 保存子切片文本和父上下文扩展结果，供前端展示和模型证据拼接。
            candidates.add(new RetrievedChunk(doc, relevanceScore, chunkText, expandContext(doc, chunkText)));
        }

        // 按身份和展示正文双重去重，避免同一切片或完全相同文本被重复返回。
        List<RetrievedChunk> deduplicated = deduplicateRetrievedChunks(candidates);
        // 当配置启用重排时，使用 query 与子切片字符重合度对候选重新排序。
        if (reportQualityProperties.getRetrieval().isRerankEnabled()) {
            // 用轻量重排结果替换原始向量相似度排序。
            deduplicated = rerankByQueryOverlap(query, deduplicated);
        }
        // 截断为最终 TopK 并返回不可变列表。
        return deduplicated.stream()
                // 控制上层推荐和前端只看到最终数量的证据。
                .limit(finalTopK)
                // 收集为列表作为召回服务输出。
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
        // 旧测试或局部构造未注入锚点服务时返回空锚点，保持纯向量召回兼容。
        if (researchQueryAnchorService == null) {
            // 空锚点包含空主题、行业、公司、代码、章节意图和命中词。
            return new QueryAnchors(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        // 正常链路委托锚点服务抽取结构化检索信号。
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
        // 创建 Spring AI SearchRequest 构造器，先设置向量检索 query 和候选数量。
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);
        // 将结构化锚点转换为 Milvus metadata scalar filter 表达式。
        FilterExpressionBuilder.Op filter = buildMetadataFilter(anchors);
        // 只有存在可用锚点时才附加过滤表达式，避免无锚点 query 被错误过滤。
        if (filter != null) {
            // 将 filter 构建为底层向量库可执行的表达式。
            builder.filterExpression(filter.build());
        }
        // 返回完整检索请求，供 VectorStore 执行相似度检索。
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
        // 创建 Spring AI filter 构建器，用于拼装 metadata 等值表达式。
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        // 报告级父标签作为强约束，限定候选报告集合。
        FilterExpressionBuilder.Op reportFilter = firstEq(builder, "reportThemeCode", anchors.themeCodes());
        // query 锚点表达式为空，后续按锚点逐步 OR 合并。
        FilterExpressionBuilder.Op queryFilter = null;
        // 主题锚点命中时按 themeCode 单值字段过滤。
        queryFilter = orFilter(builder, queryFilter, firstEq(builder, "themeCode", anchors.themeCodes()));
        // 行业锚点命中时按 industryCode 单值字段过滤。
        queryFilter = orFilter(builder, queryFilter, firstEq(builder, "industryCode", anchors.industryCodes()));
        // 股票代码锚点命中时按 ticker 单值字段过滤。
        queryFilter = orFilter(builder, queryFilter, firstEq(builder, "ticker", anchors.tickers()));
        // 返回父标签 AND query 锚点后的过滤表达式；无锚点则返回 null。
        return andFilter(builder, reportFilter, queryFilter);
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
        // 没有候选值时无法构造等值条件，直接返回 null 让调用方跳过。
        if (values == null || values.isEmpty()) {
            // 返回空表达式标识当前字段不参与过滤。
            return null;
        }
        // 使用首个结构化编码构造等值过滤，避免列表字段 contains 造成向量库兼容风险。
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
        // 左表达式为空时直接返回右表达式，用于初始化第一个条件。
        if (left == null) {
            // 当前只有右侧条件可用。
            return right;
        }
        // 右表达式为空时保持左表达式不变。
        if (right == null) {
            // 当前新增字段没有过滤值，不影响已有过滤条件。
            return left;
        }
        // 两侧都存在时使用 OR 合并，保证主题、行业、代码任一命中都可召回。
        return builder.or(left, right);
    }

    /**
     * @Description: 合并两个 metadata filter 表达式。
     * @Logic: 报告级父标签使用 AND 连接，避免被 query 锚点 OR 条件绕过。
     * @Param: builder filter 构建器；left 左表达式；right 右表达式。
     * @Return: 合并后的表达式。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private FilterExpressionBuilder.Op andFilter(FilterExpressionBuilder builder,
                                                 FilterExpressionBuilder.Op left,
                                                 FilterExpressionBuilder.Op right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return builder.and(left, right);
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
        // query 没有主题锚点时，没有必要做 MySQL 标签诊断。
        if (anchors.themeCodes().isEmpty()) {
            // 直接返回，不影响主召回链路的空结果降级。
            return;
        }
        // 查询报告级父标签主数据是否存在，用于判断是否可能是 Milvus 父标签 metadata 未同步。
        if (reportDocumentTagMapper != null) {
            reportDocumentTagMapper.countByTagCodes("THEME", anchors.themeCodes());
        }
        // 查询 chunk 标签主数据是否存在，用于判断是否可能是 Milvus chunk metadata 未同步。
        if (reportChunkTagMapper != null) {
            reportChunkTagMapper.countByTagCodes("THEME", anchors.themeCodes());
        }
    }

    /**
     * @Description: 获取可用向量库实例。
     * @Logic: 从 ObjectProvider 懒获取 VectorStore；未配置时抛出明确异常，提示 Milvus 配置缺失。
     * @Param: 无。
     * @Return: 可执行相似度检索的 VectorStore。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private VectorStore requireVectorStore() {
        // 从 Spring 容器中按需获取向量库 Bean。
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        // 未配置向量库时中断召回，避免上层误以为检索结果为空。
        if (vectorStore == null) {
            // 抛出带配置提示的异常，方便部署排查。
            throw new IllegalStateException("VectorStore is not configured. Set spring.ai.vectorstore.type=milvus and Milvus properties.");
        }
        // 返回可用向量库实例。
        return vectorStore;
    }

    /**
     * @Description: 判断候选召回分数是否低于配置阈值。
     * @Logic: 仅当分数存在且配置阈值大于 0 时启用过滤；分数缺失时保留候选交给后续护栏判断。
     * @Param: score 召回候选相关性分数。
     * @Return: 需要过滤时返回 true。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private boolean shouldFilterByScore(Double score) {
        // 同时满足分数存在、阈值启用、分数小于阈值才过滤。
        return score != null
                // 阈值大于 0 表示启用最小相似度过滤。
                && reportQualityProperties.getRetrieval().getMinSimilarityScore() > 0D
                // 低于阈值的候选被视为低相关召回。
                && score < reportQualityProperties.getRetrieval().getMinSimilarityScore();
    }

    /**
     * @Description: 对召回候选做稳定去重。
     * @Logic: 优先使用 metadata.chunkUid 或 Document id 去重；再用标题、来源、章节和切片正文去重，兜住重复入库或 metadata 缺失场景。
     * @Param: candidates 分数过滤后的候选列表。
     * @Return: 去重后的候选列表。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private List<RetrievedChunk> deduplicateRetrievedChunks(List<RetrievedChunk> candidates) {
        // 身份 key 处理同一向量记录重复返回，内容 key 处理重复入库后 UID 不同但文本完全相同的候选。
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
     * @Description: 构造召回候选身份去重键。
     * @Logic: 优先使用业务 chunkUid；Milvus 未返回该 metadata 时退回 Spring AI Document id。
     * @Param: candidate 召回候选。
     * @Return: 身份去重键；无法定位时返回空字符串。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private String identityDedupKey(RetrievedChunk candidate) {
        String chunkUid = metadataText(candidate.document(), "chunkUid");
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
     * @Description: 构造召回候选展示内容去重键。
     * @Logic: 标题、来源、章节和切片正文都一致时视为重复展示项，避免 TopK 出现多条完全相同的切片数据。
     * @Param: candidate 召回候选。
     * @Return: 内容去重键；正文为空时返回空字符串。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private String contentDedupKey(RetrievedChunk candidate) {
        String chunkText = normalizeForDedup(candidate.chunkText());
        if (chunkText.isBlank()) {
            return "";
        }
        return "content:"
                + normalizeForDedup(metadataText(candidate.document(), "title")) + "|"
                + normalizeForDedup(metadataText(candidate.document(), "source")) + "|"
                + normalizeForDedup(metadataText(candidate.document(), "sectionPath")) + "|"
                + chunkText;
    }

    /**
     * @Description: 读取 metadata 字符串值。
     * @Logic: 缺失或空值统一返回空字符串，供去重键构造复用。
     * @Param: document 召回文档；key metadata 字段名。
     * @Return: metadata 字符串。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private String metadataText(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * @Description: 规范化用于去重比较的文本。
     * @Logic: null 转空字符串，折叠空白并转小写，降低换行或空格差异造成的重复漏判。
     * @Param: text 原始文本。
     * @Return: 规范化文本。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private String normalizeForDedup(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @Description: 使用 query 与 chunk 文本的字符重合度重排候选。
     * @Logic: 规范化 query 和子切片文本后计算重合分，分数越高越靠前，用作向量召回后的轻量修正。
     * @Param: query 用户投研问题；candidates 待重排候选。
     * @Return: 重排后的候选列表。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private List<RetrievedChunk> rerankByQueryOverlap(String query, List<RetrievedChunk> candidates) {
        // 预先规范化 query，避免在排序比较中重复处理。
        String normalizedQuery = normalizeForOverlap(query);
        // 基于字符重合分降序排列候选。
        return candidates.stream()
                // 每个候选用规范化 chunkText 与 query 计算 overlapScore。
                .sorted(Comparator.comparingInt((RetrievedChunk candidate) ->
                        // 分数越高说明 query 字符在子切片中覆盖越充分。
                        overlapScore(normalizedQuery, normalizeForOverlap(candidate.chunkText()))).reversed())
                // 收集排序后的列表。
                .toList();
    }

    /**
     * @Description: 计算 query 和文本的字符重合分。
     * @Logic: query 为空或文本为空时返回 0；否则逐字符判断 query 字符是否出现在候选文本中。
     * @Param: query 规范化后的 query；text 规范化后的候选文本。
     * @Return: 字符命中数量。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private int overlapScore(String query, String text) {
        // 任一侧为空都无法计算有效重合度。
        if (query.isBlank() || text.isBlank()) {
            // 返回 0 表示没有重合证据。
            return 0;
        }
        // 初始化重合分计数器。
        int score = 0;
        // 逐字符遍历 query。
        for (int i = 0; i < query.length(); i++) {
            // 当前 query 字符出现在候选文本中时计分。
            if (text.indexOf(query.charAt(i)) >= 0) {
                // 增加一个字符命中分。
                score++;
            }
        }
        // 返回最终字符重合分。
        return score;
    }

    /**
     * @Description: 规范化用于重合度计算的文本。
     * @Logic: null 转为空字符串，移除所有空白并转小写，降低格式差异对重排的影响。
     * @Param: text 原始文本。
     * @Return: 规范化文本。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private String normalizeForOverlap(String text) {
        // 空文本返回空字符串；非空文本去空白并转小写。
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * @Description: 解析向量库返回的相关性分数。
     * @Logic: 优先读取 metadata.score；其次将 metadata.distance 转为相似度；最后读取 Document 原生 score。
     * @Param: doc Milvus 返回的候选文档。
     * @Return: 相关性分数；无法解析时返回 null。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private Double resolveRelevanceScore(Document doc) {
        // 优先读取向量库 metadata 中的 score 字段。
        Object score = doc.getMetadata().get("score");
        // score 是数字时直接转换为 double。
        if (score instanceof Number number) {
            // 返回标准 Double 分数。
            return number.doubleValue();
        }
        // 部分向量库只返回 distance 字段，需要转为相似度。
        Object distance = doc.getMetadata().get("distance");
        // distance 是数字时按 1/(1+distance) 转换为越大越相关的分数。
        if (distance instanceof Number number) {
            // distance 小于 0 时按 0 处理，避免得到大于 1 的异常分数。
            return 1.0D / (1.0D + Math.max(0D, number.doubleValue()));
        }
        // 如果 metadata 没有分数，则读取 Spring AI Document 原生 score。
        if (doc.getScore() != null) {
            // 返回 Document 自带分数。
            return doc.getScore();
        }
        // 没有任何分数字段时返回 null，让后续分数过滤跳过该候选。
        return null;
    }

    /**
     * @Description: 扩展召回子切片的父切片上下文。
     * @Logic: 有 parentChunkUid 时回查父切片文本并按配置截断；缺失父切片或父文本为空时使用子切片文本兜底。
     * @Param: doc 召回文档；fallbackText 命中子切片文本。
     * @Return: 父切片上下文或子切片兜底文本。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private String expandContext(Document doc, String fallbackText) {
        // 从 metadata 读取父切片 uid。
        String parentChunkUid = String.valueOf(doc.getMetadata().getOrDefault("parentChunkUid", ""));
        // 父 uid 缺失时无法回查父文本，直接返回子切片文本。
        if (parentChunkUid.isBlank()) {
            // 返回 fallbackText，保证 evidenceText 至少有命中子切片内容。
            return fallbackText;
        }
        // 根据父切片 uid 从 MySQL 查询父切片记录。
        ReportChunk parentChunk = reportChunkMapper.selectByChunkUid(parentChunkUid);
        // 父切片不存在或正文为空时不能扩展上下文。
        if (parentChunk == null || parentChunk.getChunkText() == null || parentChunk.getChunkText().isBlank()) {
            // 返回子切片文本作为上下文兜底。
            return fallbackText;
        }
        // 对父切片正文按配置 token 上限截断，避免 Prompt 注入过长上下文。
        return limitTokens(parentChunk.getChunkText(), reportQualityProperties.getRetrieval().getMaxParentContextTokens());
    }

    /**
     * @Description: 按阈值限制输出内容范围。
     * @Logic: 文本 token 未超限时原样返回；超限时按段落累加直到达到上限，保留完整段落边界。
     * @Param: text 待截断文本；maxTokens 最大 token 数。
     * @Return: 截断后的文本。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private String limitTokens(String text, int maxTokens) {
        // 当前文本未超过上限时直接返回，避免不必要截断。
        if (SemanticChunkUtils.estimateTokens(text) <= maxTokens) {
            // 原样返回完整父上下文。
            return text;
        }
        // 按空行切分段落，尽量保持研报语义段落完整。
        String[] paragraphs = text.split("\\n\\s*\\n");
        // 初始化截断结果缓冲区。
        StringBuilder limited = new StringBuilder();
        // 记录已加入文本的 token 数。
        int tokens = 0;
        // 逐段累加父切片内容。
        for (String paragraph : paragraphs) {
            // 估算当前段落 token 数。
            int paragraphTokens = SemanticChunkUtils.estimateTokens(paragraph);
            // 非首段且加入当前段会超限时停止追加。
            if (tokens > 0 && tokens + paragraphTokens > maxTokens) {
                // 保留已收集的完整段落，不截断段内文字。
                break;
            }
            // 非首段前补两个换行，保持段落分隔。
            if (!limited.isEmpty()) {
                // 添加段落之间的空行。
                limited.append("\n\n");
            }
            // 追加当前段落的裁剪后文本。
            limited.append(paragraph.trim());
            // 累加 token 计数。
            tokens += paragraphTokens;
        }
        // 返回按段落边界截断后的上下文文本。
        return limited.toString();
    }

    /**
     * @Description: 召回结果值对象，保存 Milvus 文档、相关性分数、子切片文本和父上下文文本。
     * @Logic: ReportRecommendService 和证据护栏服务读取该对象，分别用于前端展示、Prompt 拼接和输出前质量判断。
     * @Param: document 原始召回文档；score 相关性分数；chunkText 命中子切片文本；evidenceText 父切片上下文或兜底文本。
     */
    public record RetrievedChunk(
            /** Milvus 返回的原始 Document，包含正文和 metadata。 */
            Document document,
            /** 召回相关性分数，可能来自 score、distance 转换或 Document score。 */
            Double score,
            /** 命中的 CHILD 子切片文本，作为前端主展示文本。 */
            String chunkText,
            /** 扩展后的证据文本，优先为父切片上下文，用于模型和护栏判断。 */
            String evidenceText
    ) {
    }
}
