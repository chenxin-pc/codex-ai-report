package com.example.aimilvusweb.service.taxonomy;

import com.example.aimilvusweb.service.taxonomy.ResearchTaxonomySnapshotService.TaxonomyMatch;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Description: 投研 query 结构化锚点抽取服务，识别主题、行业、公司、代码和章节意图。
 * @Logic: 先复用数据库词库快照匹配主题/行业/公司/代码，再用轻量章节规则识别风险、逻辑、估值等 sectionPath 意图。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: query 锚点对象，供 Milvus metadata filter、重排和主题覆盖判断使用。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Service
public class ResearchQueryAnchorService {

    /** A股股票代码正则，用于在词库未覆盖时兜底识别 ticker。 */
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b\\d{6}(?:\\.(?:SH|SZ|BJ))?\\b", Pattern.CASE_INSENSITIVE);
    /** 风险章节意图关键词集合。 */
    private static final List<String> RISK_SECTION_TERMS = List.of("风险", "风险提示", "不确定性");
    /** 逻辑章节意图关键词集合。 */
    private static final List<String> LOGIC_SECTION_TERMS = List.of("逻辑", "核心逻辑", "驱动", "原因", "投资要点");
    /** 估值章节意图关键词集合。 */
    private static final List<String> VALUATION_SECTION_TERMS = List.of("估值", "目标价", "盈利预测", "财务指标");
    /** 作者锚点前缀正则，用于识别“作者/分析师/研究员”明确约束。 */
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("(?:作者|分析师|研究员|撰写人)[:：是为由\\s]*([\\p{IsHan}A-Za-z·.]{2,16})(?=的|\\s|,|，|、|$)");

    /** 结构化词库快照服务，用于匹配主题、行业、公司和代码词条。 */
    private final ResearchTaxonomySnapshotService taxonomySnapshotService;

    /**
     * @Description: 初始化 query 锚点抽取服务。
     * @Logic: 注入词库快照服务，保证 query 与 chunk 标签抽取使用同一套词库来源。
     * @Param: taxonomySnapshotService 词库快照服务。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public ResearchQueryAnchorService(ResearchTaxonomySnapshotService taxonomySnapshotService) {
        // 保存结构化词库快照服务，保证 query 锚点与标签抽取使用同一份词库。
        this.taxonomySnapshotService = taxonomySnapshotService;
    }

    /**
     * @Description: 从用户 query 中抽取结构化锚点。
     * @Logic: 词库命中按标签类型分桶；股票代码使用词库和正则双通道；章节意图根据业务关键词映射。
     * @Param: query 用户原始或规范化投研问题。
     * @Return: query 锚点结果。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public QueryAnchors extract(String query) {
        // 空 query 返回空锚点，调用方据此走输入可分析性或降级规则。
        if (query == null || query.isBlank()) {
            return QueryAnchors.empty();
        }
        // LinkedHashSet 既去重又保留词库命中的稳定顺序。
        Set<String> themeCodes = new LinkedHashSet<>();
        Set<String> industryCodes = new LinkedHashSet<>();
        Set<String> companyCodes = new LinkedHashSet<>();
        Set<String> tickers = new LinkedHashSet<>();
        Set<String> authorNames = new LinkedHashSet<>();
        Set<String> sectionIntents = new LinkedHashSet<>();
        Set<String> matchedTerms = new LinkedHashSet<>();
        // 复用词库快照匹配主题、行业、公司和代码。
        for (TaxonomyMatch match : taxonomySnapshotService.match(query)) {
            String tagType = match.entry().tagType();
            String tagCode = match.entry().tagCode();
            if ("THEME".equals(tagType)) {
                themeCodes.add(tagCode);
            } else if ("INDUSTRY".equals(tagType)) {
                industryCodes.add(tagCode);
            } else if ("COMPANY".equals(tagType)) {
                companyCodes.add(tagCode);
            } else if ("TICKER".equals(tagType)) {
                tickers.add(tagCode.toUpperCase(Locale.ROOT));
            }
            matchedTerms.add(match.entry().termText());
        }
        // 正则兜底识别股票代码，避免证券词库尚未维护时漏掉明确标的。
        Matcher matcher = TICKER_PATTERN.matcher(query);
        while (matcher.find()) {
            tickers.add(normalizeTicker(matcher.group()));
            matchedTerms.add(matcher.group());
        }
        // 仅从明确作者前缀中抽取作者锚点，降低普通人名误下推为 hard filter 的风险。
        collectAuthorAnchors(query, authorNames, matchedTerms);
        // 章节意图不落大文本 contains，只用于 sectionPath metadata 过滤或重排。
        collectSectionIntent(query, RISK_SECTION_TERMS, "RISK", sectionIntents);
        collectSectionIntent(query, LOGIC_SECTION_TERMS, "INVESTMENT_LOGIC", sectionIntents);
        collectSectionIntent(query, VALUATION_SECTION_TERMS, "VALUATION", sectionIntents);
        return new QueryAnchors(
                List.copyOf(themeCodes),
                List.copyOf(industryCodes),
                List.copyOf(companyCodes),
                List.copyOf(tickers),
                List.copyOf(authorNames),
                List.copyOf(sectionIntents),
                List.copyOf(matchedTerms)
        );
    }

    /**
     * @Description: 抽取作者锚点。
     * @Logic: 只识别带“作者/分析师/研究员/撰写人”前缀的名称，并保存规范化作者名用于 metadata filter。
     * @Param: query 用户问题；authorNames 输出作者集合；matchedTerms 输出命中词集合。
     * @Return: 无（仅写入输出集合）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private void collectAuthorAnchors(String query, Set<String> authorNames, Set<String> matchedTerms) {
        // 创建作者前缀匹配器。
        Matcher authorMatcher = AUTHOR_PATTERN.matcher(query);
        // 遍历所有明确作者锚点。
        while (authorMatcher.find()) {
            // 读取原始作者名。
            String authorName = authorMatcher.group(1);
            // 规范化作者名用于 Milvus authorText 过滤。
            String normalizedAuthorName = normalizeAuthorName(authorName);
            // 空作者名跳过。
            if (normalizedAuthorName.isBlank()) {
                continue;
            }
            // 记录规范化作者名。
            authorNames.add(normalizedAuthorName);
            // 记录原始命中词，便于诊断展示。
            matchedTerms.add(authorName);
        }
    }

    /**
     * @Description: 根据关键词识别章节意图。
     * @Logic: query 命中任一关键词时加入对应意图编码，后续用于 sectionPath 加权或过滤。
     * @Param: query 用户问题；terms 关键词集合；intent 章节意图编码；target 输出集合。
     * @Return: 无（仅向 target 添加意图）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private void collectSectionIntent(String query, List<String> terms, String intent, Set<String> target) {
        // 标准化 query，避免空格影响关键词命中。
        String normalizedQuery = normalize(query);
        // 任一关键词命中即可加入章节意图。
        boolean matched = terms.stream().map(this::normalize).anyMatch(normalizedQuery::contains);
        if (matched) {
            target.add(intent);
        }
    }

    /**
     * @Description: 标准化股票代码。
     * @Logic: 统一转大写；未带交易所后缀时保留六位代码，避免猜测交易所造成误判。
     * @Param: ticker 原始股票代码文本。
     * @Return: 标准化代码。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private String normalizeTicker(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * @Description: 规范化作者名。
     * @Logic: 去除空白并转小写，与导入作者保存逻辑保持一致。
     * @Param: authorName 原始作者名。
     * @Return: 规范化作者名。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String normalizeAuthorName(String authorName) {
        return authorName == null ? "" : authorName.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * @Description: 规范化文本用于关键词匹配。
     * @Logic: null 转空字符串，移除空白并转小写，降低格式差异。
     * @Param: text 原始文本。
     * @Return: 规范化文本。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * @Description: query 结构化锚点值对象，集中表达主题、行业、公司、代码、作者和章节意图。
     * @Logic: 检索、证据质量和前端展示共享该对象，避免各链路重复解析 query。
     * @Param: themeCodes 主题编码；industryCodes 行业编码；companyCodes 公司编码；tickers 股票代码；authorNames 规范化作者名；sectionIntents 章节意图；matchedTerms 命中词条。
     */
    public record QueryAnchors(
            /** query 命中的主题编码列表。 */
            List<String> themeCodes,
            /** query 命中的行业编码列表。 */
            List<String> industryCodes,
            /** query 命中的公司编码列表。 */
            List<String> companyCodes,
            /** query 命中的股票代码列表。 */
            List<String> tickers,
            /** query 命中的规范化作者名列表。 */
            List<String> authorNames,
            /** query 命中的章节意图列表。 */
            List<String> sectionIntents,
            /** query 命中的原始词条列表。 */
            List<String> matchedTerms
    ) {
        /**
         * @Description: 构造空 query 锚点。
         * @Logic: 空输入或无命中时返回空集合，调用方无需判空。
         * @Return: 空锚点对象。
         */
        private static QueryAnchors empty() {
            return new QueryAnchors(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        /**
         * @Description: 判断是否存在可用于检索过滤的结构化锚点。
         * @Logic: 主题、行业、公司、代码或章节意图任一存在即认为可构造过滤或加权条件。
         * @Return: 存在结构化锚点时返回 true。
         */
        public boolean hasAnyAnchor() {
            // 任一主题、行业、公司、代码或章节意图存在，都说明 query 有结构化检索信号。
            return !themeCodes.isEmpty()
                    // 行业编码存在时可参与 metadata filter 或覆盖判断。
                    || !industryCodes.isEmpty()
                    // 公司编码存在时可用于后续扩展公司级过滤。
                    || !companyCodes.isEmpty()
                    // 股票代码存在时可用于精确标的过滤。
                    || !tickers.isEmpty()
                    // 作者名存在时可用于 author metadata 过滤或加权。
                    || !authorNames.isEmpty()
                    // 章节意图存在时可用于 sectionPath 过滤或重排。
                    || !sectionIntents.isEmpty();
        }

        /**
         * @Description: 返回前端可展示的锚点摘要。
         * @Logic: 将结构化编码和命中词条合并为稳定列表，便于 evidenceQuality 展示。
         * @Return: 锚点摘要列表。
         */
        public List<String> summary() {
            // 初始化可展示锚点摘要列表。
            List<String> values = new ArrayList<>();
            // 追加主题编码。
            values.addAll(themeCodes);
            // 追加行业编码。
            values.addAll(industryCodes);
            // 追加公司编码。
            values.addAll(companyCodes);
            // 追加股票代码。
            values.addAll(tickers);
            // 追加作者名。
            values.addAll(authorNames);
            // 追加章节意图。
            values.addAll(sectionIntents);
            // 追加原始命中词条。
            values.addAll(matchedTerms);
            // 过滤空值并去重，返回稳定摘要。
            return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        }
    }
}
