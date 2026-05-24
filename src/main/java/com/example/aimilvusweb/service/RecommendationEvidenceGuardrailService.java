package com.example.aimilvusweb.service;

import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.enums.QueryIntentEnum;
import com.example.aimilvusweb.enums.RecommendationOutputLevelEnum;
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

    /**
     * @Description: 初始化证据质量护栏依赖。
     * @Logic: 保存词典服务与配置对象，用于相关性阈值、行业词和禁用语判断。
     * @Param: dictionaryService 词典服务；reportQualityProperties 研报质量配置。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public RecommendationEvidenceGuardrailService(QueryGuardrailDictionaryService dictionaryService,
                                                  ReportQualityProperties reportQualityProperties) {
        this.dictionaryService = dictionaryService;
        this.reportQualityProperties = reportQualityProperties;
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
        List<String> issues = new ArrayList<>();
        boolean evidencePresent = hasEvidence(retrievedChunks);
        boolean queryRelevant = evidencePresent && isQueryRelevant(query, retrievedChunks);
        EntitySignals entitySignals = extractEntitySignals(retrievedChunks);
        boolean entityConsistent = isEntityConsistent(intent, entitySignals);
        boolean dataConsistent = isDataConsistent(intent, entitySignals, retrievedChunks);
        boolean citationComplete = evidencePresent && entityConsistent;

        if (!evidencePresent) {
            issues.add("EVIDENCE_MISSING");
        }
        if (!queryRelevant) {
            issues.add("LOW_QUERY_RELEVANCE");
        }
        if (!entityConsistent) {
            issues.add("ENTITY_CONFLICT");
        }
        if (!dataConsistent) {
            issues.add("DATA_CONFLICT");
        }
        if (!citationComplete) {
            issues.add("CITATION_INCOMPLETE");
        }

        RecommendationOutputLevelEnum outputLevel = resolveOutputLevel(intent, evidencePresent, queryRelevant, entityConsistent, dataConsistent, citationComplete);
        RecommendRespDTO.EvidenceQualityRespDTO quality = new RecommendRespDTO.EvidenceQualityRespDTO(
                evidencePresent,
                queryRelevant,
                entityConsistent,
                dataConsistent,
                citationComplete,
                List.copyOf(issues)
        );
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
        String normalized = normalize(text);
        return dictionaryService.forbiddenRecommendationPhrases().stream()
                .map(this::normalize)
                .filter(term -> !term.isBlank())
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
                                                             boolean citationComplete) {
        if (!evidencePresent || !entityConsistent || !citationComplete) {
            return RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED;
        }
        if (QueryIntentEnum.THEME_RESEARCH.equals(intent)) {
            return RecommendationOutputLevelEnum.L2_THEME_RESEARCH;
        }
        if (!queryRelevant || !dataConsistent) {
            return RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED;
        }
        return RecommendationOutputLevelEnum.L3_FULL_ANALYSIS;
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
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }
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
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank()) {
            return false;
        }
        int bestScore = 0;
        for (ReportRetrievalService.RetrievedChunk chunk : chunks) {
            String haystack = normalize(combinedText(chunk));
            bestScore = Math.max(bestScore, overlapScore(normalizedQuery, haystack));
        }
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
        int score = 0;
        Set<Integer> visited = new LinkedHashSet<>();
        query.codePoints().forEach(visited::add);
        for (Integer codePoint : visited) {
            if (Character.isLetterOrDigit(codePoint) && text.indexOf(new String(Character.toChars(codePoint))) >= 0) {
                score++;
            }
        }
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
        Set<String> tickers = new LinkedHashSet<>();
        Set<String> exchanges = new LinkedHashSet<>();
        Set<String> industries = new LinkedHashSet<>();
        Set<String> relatedTerms = new LinkedHashSet<>();
        if (chunks == null) {
            return new EntitySignals(tickers, exchanges, industries, relatedTerms);
        }
        for (ReportRetrievalService.RetrievedChunk chunk : chunks) {
            String text = combinedText(chunk);
            Matcher matcher = TICKER_PATTERN.matcher(text);
            while (matcher.find()) {
                tickers.add(matcher.group().toUpperCase(Locale.ROOT));
            }
            collectTerms(text, EXCHANGE_TERMS, exchanges);
            collectTerms(text, dictionaryService.industryTerms(), industries);
            collectTerms(text, dictionaryService.industryTerms(), relatedTerms);
            collectTerms(text, dictionaryService.themeTerms(), relatedTerms);
        }
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
        String normalizedText = normalize(text);
        for (String term : terms) {
            if (!term.isBlank() && normalizedText.contains(normalize(term))) {
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
        if (QueryIntentEnum.THEME_RESEARCH.equals(intent)) {
            return true;
        }
        return signals.tickers().size() <= 1
                && signals.exchanges().size() <= 1
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
        if (QueryIntentEnum.THEME_RESEARCH.equals(intent)) {
            return true;
        }
        boolean hasMarketData = chunks != null && chunks.stream().anyMatch(chunk -> containsAny(combinedText(chunk), MARKET_DATA_TERMS));
        if (!hasMarketData) {
            return isEntityConsistent(intent, signals);
        }
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
        String normalized = normalize(text);
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
        Document doc = chunk.document();
        String title = String.valueOf(doc.getMetadata().getOrDefault("title", ""));
        String source = String.valueOf(doc.getMetadata().getOrDefault("source", ""));
        String section = String.valueOf(doc.getMetadata().getOrDefault("sectionPath", ""));
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
