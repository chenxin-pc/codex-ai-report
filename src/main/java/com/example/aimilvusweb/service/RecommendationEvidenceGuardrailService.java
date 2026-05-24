package com.example.aimilvusweb.service;

import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.enums.QueryIntentEnum;
import com.example.aimilvusweb.enums.RecommendationOutputLevelEnum;
import com.example.aimilvusweb.service.ResearchQueryAnchorService.QueryAnchors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Description: 推荐证据质量护栏服务，基于召回结果生成输出等级与降级原因。
 * @Logic: 检查证据存在性、query相关性、主体一致性、数据一致性和引用完整性，并按硬闸门降级。
 * @author: cx
 * @Date: 2026-05-24 00:45:00
 */
@Service
public class RecommendationEvidenceGuardrailService {

    /** 证据文本中的A股股票代码识别正则，支持可选交易所后缀。 */
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b\\d{6}(?:\\.(?:SH|SZ|BJ))?\\b", Pattern.CASE_INSENSITIVE);
    /** 交易所关键词列表，用于识别北交所、深交所、上交所等交易所冲突。 */
    private static final List<String> EXCHANGE_TERMS = List.of("北交所", "深交所", "上交所", "BSE", "SZSE", "SSE");
    /** 市场和估值数据关键词列表，用于判断股价、市值、PE等指标是否可能错配主体。 */
    private static final List<String> MARKET_DATA_TERMS = List.of("股价", "总市值", "市值", "PE", "P/E", "PB", "P/B", "市盈率", "市净率", "目标价", "EPS");

    /** 本地词典服务，提供行业词、主题词和禁用推荐短语。 */
    private final QueryGuardrailDictionaryService dictionaryService;
    /** 研报质量配置，提供query相关性阈值等护栏参数。 */
    private final ReportQualityProperties reportQualityProperties;
    /** query 锚点抽取服务，用于判断主题类问题是否被召回证据覆盖。 */
    private final ResearchQueryAnchorService researchQueryAnchorService;

    /**
     * @Description: 初始化证据质量护栏依赖。
     * @Logic: 保存词典服务与配置对象，用于相关性阈值、行业词和禁用语判断。
     * @Param: dictionaryService 词典服务；reportQualityProperties 研报质量配置。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    @Autowired
    public RecommendationEvidenceGuardrailService(QueryGuardrailDictionaryService dictionaryService,
                                                  ReportQualityProperties reportQualityProperties,
                                                  ResearchQueryAnchorService researchQueryAnchorService) {
        // 保存词典服务，后续用于行业词、主题词和禁用推荐语匹配。
        this.dictionaryService = dictionaryService;
        // 保存质量配置，后续读取 query 相关性阈值等参数。
        this.reportQualityProperties = reportQualityProperties;
        // 保存 query 锚点抽取服务，主题覆盖判断需要复用结构化锚点。
        this.researchQueryAnchorService = researchQueryAnchorService;
    }

    /**
     * @Description: 兼容旧测试的证据护栏构造器。
     * @Logic: 未注入 query 锚点服务时保留原有相关性和主体一致性判断，主题覆盖使用文本词典兜底。
     * @Param: dictionaryService 本地词典服务；reportQualityProperties 研报质量配置。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public RecommendationEvidenceGuardrailService(QueryGuardrailDictionaryService dictionaryService,
                                                  ReportQualityProperties reportQualityProperties) {
        this(dictionaryService, reportQualityProperties, null);
    }

    /**
     * @Description: 根据输入意图和召回结果评估推荐输出等级。
     * @Logic: 空证据直接L1；主题输入默认L2；完整分析输入要求相关性、主体一致性、数据一致性和引用完整性均通过。
     * @Param: query 原始问题；intent 输入意图；retrievedChunks 召回结果。
     * @Return: 证据质量决策，包含输出等级、质量状态和降级原因。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public EvidenceDecision evaluate(String query,
                                     QueryIntentEnum intent,
                                     List<ReportRetrievalService.RetrievedChunk> retrievedChunks) {
        // 初始化证据质量问题码列表。
        List<String> issues = new ArrayList<>();
        // 判断召回结果中是否存在可用证据文本。
        boolean evidencePresent = hasEvidence(retrievedChunks);
        // 只有存在证据时才计算 query 相关性，否则相关性直接为 false。
        boolean queryRelevant = evidencePresent && isQueryRelevant(query, retrievedChunks);
        // 从召回证据中抽取股票代码、交易所、行业和主题相关词。
        EntitySignals entitySignals = extractEntitySignals(retrievedChunks);
        // 根据输入意图判断召回证据主体是否一致。
        boolean entityConsistent = isEntityConsistent(intent, entitySignals);
        // 判断股价、市值、估值等数据口径是否与主体一致。
        boolean dataConsistent = isDataConsistent(intent, entitySignals, retrievedChunks);
        // 当前引用完整性简化为存在证据且主体一致，避免跨主体引用进入完整分析。
        boolean citationComplete = evidencePresent && entityConsistent;
        // 抽取 query 主题、行业、代码等结构化锚点。
        QueryAnchors anchors = extractAnchors(query);
        // 判断主题类 query 的召回结果是否覆盖主题或行业锚点。
        boolean themeCovered = isThemeCovered(intent, anchors, retrievedChunks);

        // 无证据时记录证据缺失问题。
        if (!evidencePresent) {
            // 标记为 EVIDENCE_MISSING，后续会降级到 L1。
            issues.add("EVIDENCE_MISSING");
        }
        // query 与证据低相关时记录相关性问题。
        if (!queryRelevant) {
            // 标记为 LOW_QUERY_RELEVANCE，提示召回结果可能跑偏。
            issues.add("LOW_QUERY_RELEVANCE");
        }
        // 主体不一致时记录实体冲突问题。
        if (!entityConsistent) {
            // 标记为 ENTITY_CONFLICT，表示多标的或交易所/行业冲突。
            issues.add("ENTITY_CONFLICT");
        }
        // 数据口径不一致时记录数据冲突问题。
        if (!dataConsistent) {
            // 标记为 DATA_CONFLICT，避免错配股价、市值、估值等数据。
            issues.add("DATA_CONFLICT");
        }
        // 引用不完整时记录引用问题。
        if (!citationComplete) {
            // 标记为 CITATION_INCOMPLETE，表示证据无法可靠锚定。
            issues.add("CITATION_INCOMPLETE");
        }
        // 主题覆盖不足时记录主题召回问题。
        if (!themeCovered) {
            // 标记为 LOW_THEME_COVERAGE，提示 Milvus metadata 未覆盖 query 主题锚点。
            issues.add("LOW_THEME_COVERAGE");
        }

        // 将证据质量布尔值映射为最终输出等级。
        RecommendationOutputLevelEnum outputLevel = resolveOutputLevel(intent, evidencePresent, queryRelevant, entityConsistent, dataConsistent, citationComplete, themeCovered);
        // 构建响应给前端和 Prompt 的证据质量详情。
        RecommendRespDTO.EvidenceQualityRespDTO quality = new RecommendRespDTO.EvidenceQualityRespDTO(
                // 是否存在可用证据。
                evidencePresent,
                // 证据是否与 query 基本相关。
                queryRelevant,
                // 召回证据主体是否一致。
                entityConsistent,
                // 市场和财务数据口径是否一致。
                dataConsistent,
                // 引用和主体锚定是否完整。
                citationComplete,
                // 主题研究是否覆盖 query 锚点。
                themeCovered,
                // query 结构化锚点摘要。
                anchors.summary(),
                // 证据质量问题码。
                List.copyOf(issues)
        );
        // 返回输出等级、质量详情、降级原因和召回相关词。
        return new EvidenceDecision(outputLevel, quality, List.copyOf(issues), entitySignals.relatedTerms());
    }

    /**
     * @Description: 检查文本是否包含禁用的高确定性投资建议。
     * @Logic: 从本地禁用词典读取短语，命中任一短语即视为需要保护。
     * @Param: text 待检查输出文本。
     * @Return: 命中禁用建议语时返回true。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public boolean containsForbiddenRecommendation(String text) {
        // 标准化待检查文本，降低空白和大小写差异。
        String normalized = normalize(text);
        // 遍历禁用推荐短语词典并判断是否命中。
        return dictionaryService.forbiddenRecommendationPhrases().stream()
                // 规范化禁用短语。
                .map(this::normalize)
                // 忽略空词条，避免空字符串误命中。
                .filter(term -> !term.isBlank())
                // 任一禁用短语出现在文本中即触发保护。
                .anyMatch(normalized::contains);
    }

    /**
     * @Description: 根据证据质量布尔值解析最终输出等级。
     * @Logic: 无证据、主体冲突或引用不完整优先降为 L1；主题输入通过硬闸门后为 L2；完整分析要求相关性和数据一致性通过。
     * @Param: intent 输入意图；evidencePresent 是否有证据；queryRelevant 是否相关；entityConsistent 主体是否一致；dataConsistent 数据是否一致；citationComplete 引用是否完整。
     * @Return: 推荐输出等级。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private RecommendationOutputLevelEnum resolveOutputLevel(QueryIntentEnum intent,
                                                             boolean evidencePresent,
                                                             boolean queryRelevant,
                                                             boolean entityConsistent,
                                                             boolean dataConsistent,
                                                             boolean citationComplete,
                                                             boolean themeCovered) {
        // 证据缺失、主体冲突、引用不完整或主题覆盖不足都属于硬闸门失败。
        if (!evidencePresent || !entityConsistent || !citationComplete || !themeCovered) {
            // 硬闸门失败时只能输出 L1 证据不足/污染说明。
            return RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED;
        }
        // 主题研究通过硬闸门后允许 L2 输出相关公司候选和主题分析。
        if (QueryIntentEnum.THEME_RESEARCH.equals(intent)) {
            // L2 禁止买卖评级、目标价、仓位等高确定性建议。
            return RecommendationOutputLevelEnum.L2_THEME_RESEARCH;
        }
        // 直接分析还要求 query 相关性和数据一致性通过。
        if (!queryRelevant || !dataConsistent) {
            // 相关性或数据口径不通过时降级为 L1。
            return RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED;
        }
        // 所有硬闸门和直接分析条件均通过时允许完整分析。
        return RecommendationOutputLevelEnum.L3_FULL_ANALYSIS;
    }

    /**
     * @Description: 抽取 query 结构化锚点。
     * @Logic: 优先使用结构化词库服务；服务未注入时返回空锚点，保持旧链路兼容。
     * @Param: query 用户输入。
     * @Return: query 锚点对象。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private QueryAnchors extractAnchors(String query) {
        // 兼容旧测试或旧构造器没有注入锚点服务的情况。
        if (researchQueryAnchorService == null) {
            // 返回空锚点，主题覆盖判断会按无锚点处理。
            return new QueryAnchors(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        // 正常链路委托结构化锚点服务抽取 query 标签。
        return researchQueryAnchorService.extract(query);
    }

    /**
     * @Description: 判断主题类 query 是否被召回证据覆盖。
     * @Logic: 非主题研究不启用主题硬闸门；主题锚点存在时要求召回 metadata 覆盖对应主题或强相关行业/代码。
     * @Param: intent 输入意图；anchors query 锚点；chunks 召回结果。
     * @Return: 主题覆盖通过时返回 true。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private boolean isThemeCovered(QueryIntentEnum intent,
                                   QueryAnchors anchors,
                                   List<ReportRetrievalService.RetrievedChunk> chunks) {
        // 非主题研究不启用主题覆盖硬闸门。
        if (!QueryIntentEnum.THEME_RESEARCH.equals(intent)) {
            // 直接分析只依赖主体、相关性和数据一致性判断。
            return true;
        }
        // query 没有主题和行业锚点时无法要求 metadata 覆盖，视为通过。
        if (anchors.themeCodes().isEmpty() && anchors.industryCodes().isEmpty()) {
            // 允许无结构化锚点的主题输入继续走其他质量判断。
            return true;
        }
        // 有主题/行业锚点但没有召回结果时主题覆盖失败。
        if (chunks == null || chunks.isEmpty()) {
            // 返回 false 触发 LOW_THEME_COVERAGE。
            return false;
        }
        // 遍历召回 chunk，检查 metadata 是否覆盖主题或行业锚点。
        for (ReportRetrievalService.RetrievedChunk chunk : chunks) {
            // 主题编码命中 themeCode 或 themeCodes 时视为覆盖。
            if (metadataIntersects(chunk.document(), "themeCode", "themeCodes", anchors.themeCodes())) {
                // 任一 chunk 覆盖主题即通过。
                return true;
            }
            // 行业编码命中 industryCode 或 industryCodes 时也视为覆盖。
            if (metadataIntersects(chunk.document(), "industryCode", "industryCodes", anchors.industryCodes())) {
                // 任一 chunk 覆盖行业即通过。
                return true;
            }
        }
        // 所有召回 chunk 都未覆盖 query 锚点，主题覆盖失败。
        return false;
    }

    /**
     * @Description: 判断文档 metadata 是否覆盖 query 锚点。
     * @Logic: 同时兼容主标量字段和列表摘要字段；任一值相等即认为覆盖。
     * @Param: document 召回文档；primaryKey 主标量字段；listKey 列表字段；expectedValues query 锚点值。
     * @Return: metadata 覆盖锚点时返回 true。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private boolean metadataIntersects(Document document, String primaryKey, String listKey, List<String> expectedValues) {
        // query 没有期望值时没有可比较对象。
        if (expectedValues == null || expectedValues.isEmpty()) {
            // 返回 false，调用方自行决定无锚点是否跳过。
            return false;
        }
        // 使用集合收集 metadata 实际值，便于去重和 contains 判断。
        Set<String> actualValues = new LinkedHashSet<>();
        // 加入主标量字段值，例如 themeCode。
        actualValues.addAll(metadataValues(document.getMetadata().get(primaryKey)));
        // 加入列表摘要字段值，例如 themeCodes。
        actualValues.addAll(metadataValues(document.getMetadata().get(listKey)));
        // 任一 query 锚点出现在实际 metadata 值中即认为覆盖。
        return expectedValues.stream().anyMatch(actualValues::contains);
    }

    /**
     * @Description: 从 metadata 值中抽取字符串集合。
     * @Logic: 兼容 String、Iterable 和其他对象类型，统一转为字符串用于锚点比较。
     * @Param: value metadata 原始值。
     * @Return: 字符串值集合。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private Set<String> metadataValues(Object value) {
        // 初始化实际值集合。
        Set<String> values = new LinkedHashSet<>();
        // metadata 为集合时逐项读取。
        if (value instanceof Iterable<?> iterable) {
            // 遍历集合元素。
            for (Object item : iterable) {
                // 只保留非空且非空白值。
                if (item != null && !String.valueOf(item).isBlank()) {
                    // 将元素转为字符串加入集合。
                    values.add(String.valueOf(item));
                }
            }
            // 返回集合型 metadata 的所有有效值。
            return values;
        }
        // metadata 为单值时直接转字符串。
        if (value != null && !String.valueOf(value).isBlank()) {
            // 加入单值 metadata。
            values.add(String.valueOf(value));
        }
        // 返回实际值集合。
        return values;
    }

    /**
     * @Description: 判断召回结果是否包含可用证据文本。
     * @Logic: 空集合直接失败；任一 chunkText 或 evidenceText 非空即可认为存在可展示证据。
     * @Param: chunks 召回结果列表。
     * @Return: 存在可用证据文本时返回 true。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private boolean hasEvidence(List<ReportRetrievalService.RetrievedChunk> chunks) {
        // 召回列表为空或 null 时没有证据。
        if (chunks == null || chunks.isEmpty()) {
            // 返回 false 触发 EVIDENCE_MISSING。
            return false;
        }
        // 任一 chunk 的子切片文本或父上下文非空，即认为存在可用证据。
        return chunks.stream().anyMatch(chunk -> !safeText(chunk.chunkText()).isBlank() || !safeText(chunk.evidenceText()).isBlank());
    }

    /**
     * @Description: 判断召回证据与 query 是否具备基本相关性。
     * @Logic: 取 query 与每条证据组合文本的最大字符重合分数，并与配置阈值比较。
     * @Param: query 用户输入；chunks 召回结果列表。
     * @Return: 最大重合分达到阈值时返回 true。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private boolean isQueryRelevant(String query, List<ReportRetrievalService.RetrievedChunk> chunks) {
        // 规范化 query 用于字符重合度计算。
        String normalizedQuery = normalize(query);
        // 空 query 无法计算相关性。
        if (normalizedQuery.isBlank()) {
            // 返回 false 触发低相关降级。
            return false;
        }
        // 初始化最佳重合分。
        int bestScore = 0;
        // 遍历所有召回 chunk，取最高相关性得分。
        for (ReportRetrievalService.RetrievedChunk chunk : chunks) {
            // 拼接并规范化当前 chunk 的标题、来源、章节、子文本和父上下文。
            String haystack = normalize(combinedText(chunk));
            // 计算当前 chunk 与 query 的重合分，并更新最大值。
            bestScore = Math.max(bestScore, overlapScore(normalizedQuery, haystack));
        }
        // 最大重合分达到配置阈值时认为 query 与证据基本相关。
        return bestScore >= reportQualityProperties.getQueryGuardrail().getMinQueryOverlapScore();
    }

    /**
     * @Description: 计算 query 与证据文本的去重字符重合分数。
     * @Logic: 对 query 字符去重后，仅统计字母和数字在证据文本中的出现情况，避免重复字符放大分数。
     * @Param: query 规范化后的查询文本；text 规范化后的证据文本。
     * @Return: 字符重合数量。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private int overlapScore(String query, String text) {
        // 初始化重合分。
        int score = 0;
        // 使用集合去重 query 字符，避免重复字符放大相关性。
        Set<Integer> visited = new LinkedHashSet<>();
        // 将 query 所有 code point 写入去重集合。
        query.codePoints().forEach(visited::add);
        // 遍历去重后的 query 字符。
        for (Integer codePoint : visited) {
            // 仅统计字母和数字，并判断该字符是否出现在证据文本中。
            if (Character.isLetterOrDigit(codePoint) && text.indexOf(new String(Character.toChars(codePoint))) >= 0) {
                // 命中一个字符则加一分。
                score++;
            }
        }
        // 返回去重字符重合分。
        return score;
    }

    /**
     * @Description: 从召回结果中抽取主体和主题信号。
     * @Logic: 组合标题、来源、章节、chunk 和 parent 上下文后，提取股票代码、交易所、行业词和主题相关词。
     * @Param: chunks 召回结果列表。
     * @Return: 主体信号集合，包含代码、交易所、行业和相关主题词。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private EntitySignals extractEntitySignals(List<ReportRetrievalService.RetrievedChunk> chunks) {
        // 初始化股票代码集合。
        Set<String> tickers = new LinkedHashSet<>();
        // 初始化交易所集合。
        Set<String> exchanges = new LinkedHashSet<>();
        // 初始化行业集合。
        Set<String> industries = new LinkedHashSet<>();
        // 初始化主题或行业相关词集合。
        Set<String> relatedTerms = new LinkedHashSet<>();
        // 召回为空时返回空信号，后续质量判断会结合证据存在性处理。
        if (chunks == null) {
            // 返回空主体信号对象。
            return new EntitySignals(tickers, exchanges, industries, relatedTerms);
        }
        // 遍历召回 chunk，抽取主体和主题信号。
        for (ReportRetrievalService.RetrievedChunk chunk : chunks) {
            // 拼接当前 chunk 的可检索文本。
            String text = combinedText(chunk);
            // 使用股票代码正则扫描文本。
            Matcher matcher = TICKER_PATTERN.matcher(text);
            // 遍历所有股票代码命中。
            while (matcher.find()) {
                // 股票代码统一转大写后加入集合。
                tickers.add(matcher.group().toUpperCase(Locale.ROOT));
            }
            // 收集交易所关键词。
            collectTerms(text, EXCHANGE_TERMS, exchanges);
            // 收集行业关键词。
            collectTerms(text, dictionaryService.industryTerms(), industries);
            // 行业词也作为相关主题词返回。
            collectTerms(text, dictionaryService.industryTerms(), relatedTerms);
            // 收集主题词作为相关主题词返回。
            collectTerms(text, dictionaryService.themeTerms(), relatedTerms);
        }
        // 返回抽取后的主体和主题信号。
        return new EntitySignals(tickers, exchanges, industries, relatedTerms);
    }

    /**
     * @Description: 将命中的词典项收集到目标集合。
     * @Logic: 先规范化待检文本，再逐个规范化词典项并做包含匹配，命中后保留原始词典项。
     * @Param: text 待检文本；terms 词典项；target 命中结果集合。
     * @Return: 无（仅向 target 添加命中词）。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private void collectTerms(String text, List<String> terms, Set<String> target) {
        // 规范化待检文本。
        String normalizedText = normalize(text);
        // 遍历候选术语。
        for (String term : terms) {
            // 术语非空且出现在文本中时视为命中。
            if (!term.isBlank() && normalizedText.contains(normalize(term))) {
                // 保留原始术语文本，便于前端或诊断展示。
                target.add(term);
            }
        }
    }

    /**
     * @Description: 判断主体信号是否一致。
     * @Logic: 主题研究允许多主体候选；单标的分析要求股票代码、交易所和行业信号最多各一个。
     * @Param: intent 输入意图；signals 抽取出的主体信号。
     * @Return: 主体信号未冲突时返回 true。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private boolean isEntityConsistent(QueryIntentEnum intent, EntitySignals signals) {
        // 主题研究允许出现多个公司或行业候选。
        if (QueryIntentEnum.THEME_RESEARCH.equals(intent)) {
            // 返回 true，避免主题研究被多主体候选误降级。
            return true;
        }
        // 单标的分析要求股票代码、交易所和行业信号最多各一个。
        return signals.tickers().size() <= 1
                // 交易所关键词最多一个，避免北交所/深交所等混杂。
                && signals.exchanges().size() <= 1
                // 行业关键词最多一个，避免港口和医药等跨行业混杂。
                && signals.industries().size() <= 1;
    }

    /**
     * @Description: 判断财务和市场指标是否可归属于同一主体。
     * @Logic: 主题研究默认允许多主体；单标的分析在存在股价、市值、估值等指标时要求实体信号一致。
     * @Param: intent 输入意图；signals 主体信号；chunks 召回结果列表。
     * @Return: 数据口径未发现错配时返回 true。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private boolean isDataConsistent(QueryIntentEnum intent,
                                     EntitySignals signals,
                                     List<ReportRetrievalService.RetrievedChunk> chunks) {
        // 主题研究默认允许多主体和多口径候选。
        if (QueryIntentEnum.THEME_RESEARCH.equals(intent)) {
            // 返回 true，让 L2 主题分析不因多公司数据而降级。
            return true;
        }
        // 判断召回证据中是否出现股价、市值、估值、EPS 等市场数据。
        boolean hasMarketData = chunks != null && chunks.stream().anyMatch(chunk -> containsAny(combinedText(chunk), MARKET_DATA_TERMS));
        // 没有市场数据时，只要求实体信号不冲突。
        if (!hasMarketData) {
            // 复用主体一致性判断。
            return isEntityConsistent(intent, signals);
        }
        // 有市场数据时严格要求股票代码、交易所和行业信号均不混杂。
        return signals.tickers().size() <= 1 && signals.exchanges().size() <= 1 && signals.industries().size() <= 1;
    }

    /**
     * @Description: 判断文本是否包含任一指定术语。
     * @Logic: 将文本和术语统一规范化后执行 contains 匹配，用于识别市场指标类词汇。
     * @Param: text 待检文本；terms 候选术语。
     * @Return: 命中任一术语时返回 true。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private boolean containsAny(String text, List<String> terms) {
        // 规范化待检文本。
        String normalized = normalize(text);
        // 任一候选术语规范化后出现在文本中即返回 true。
        return terms.stream().map(this::normalize).anyMatch(normalized::contains);
    }

    /**
     * @Description: 组合召回 chunk 的可检索文本。
     * @Logic: 将标题、来源、章节、子 chunk 和 parent 上下文拼接，用于主体、相关性和数据一致性检查。
     * @Param: chunk 单条召回结果。
     * @Return: 合并后的证据文本。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private String combinedText(ReportRetrievalService.RetrievedChunk chunk) {
        // 获取召回结果的原始 Document。
        Document doc = chunk.document();
        // 从 metadata 读取标题，缺失时为空字符串。
        String title = String.valueOf(doc.getMetadata().getOrDefault("title", ""));
        // 从 metadata 读取来源，缺失时为空字符串。
        String source = String.valueOf(doc.getMetadata().getOrDefault("source", ""));
        // 从 metadata 读取章节路径，缺失时为空字符串。
        String section = String.valueOf(doc.getMetadata().getOrDefault("sectionPath", ""));
        // 拼接标题、来源、章节、子切片和父上下文，形成证据质量检查文本。
        return title + "\n" + source + "\n" + section + "\n" + safeText(chunk.chunkText()) + "\n" + safeText(chunk.evidenceText());
    }

    /**
     * @Description: 标准化文本用于规则和证据质量匹配。
     * @Logic: null 转空字符串，移除所有空白并转小写，减少格式差异对匹配结果的影响。
     * @Param: text 原始文本。
     * @Return: 规范化后的文本。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private String normalize(String text) {
        // null 转空字符串；非空文本移除空白并转小写。
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * @Description: 安全获取文本值。
     * @Logic: null 转为空字符串，其余文本原样返回，避免证据拼接和判空时出现空指针。
     * @Param: text 原始文本。
     * @Return: 非 null 文本。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private String safeText(String text) {
        // null 文本转为空字符串，非空文本保持原样。
        return text == null ? "" : text;
    }

    /**
     * @Description: 证据质量评估结果。
     * @Logic: evaluate 方法统一返回该对象，推荐服务据此决定输出等级、展示质量详情并注入Prompt约束。
     * @Param: outputLevel 推荐输出等级；quality 证据质量布尔详情；degradationReasons 降级原因码；relatedTerms 从证据中抽取的主题相关词。
     */
    public record EvidenceDecision(
            /** 证据质量最终映射出的推荐输出等级。 */
            RecommendationOutputLevelEnum outputLevel,
            /** 证据质量布尔详情，供接口响应、前端展示和Prompt约束使用。 */
            RecommendRespDTO.EvidenceQualityRespDTO quality,
            /** 降级原因码列表，用于解释为什么限制输出强度。 */
            List<String> degradationReasons,
            /** 从证据中抽取的行业或主题相关词，用于后续扩展主题研究展示。 */
            Set<String> relatedTerms
    ) {
    }

    /**
     * @Description: 从召回证据中抽取的主体和主题信号集合。
     * @Logic: 用于判断多标的混杂、交易所冲突、行业冲突以及主题研究相关词。
     * @Param: tickers 股票代码集合；exchanges 交易所集合；industries 行业集合；relatedTerms 主题或行业相关词集合。
     */
    private record EntitySignals(
            /** 从召回证据中抽取到的股票代码集合。 */
            Set<String> tickers,
            /** 从召回证据中抽取到的交易所关键词集合。 */
            Set<String> exchanges,
            /** 从召回证据中抽取到的行业关键词集合。 */
            Set<String> industries,
            /** 从召回证据中抽取到的行业或主题相关词集合。 */
            Set<String> relatedTerms
    ) {
    }
}
