package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.retrieval.RetrievedChunk;
import com.example.aimilvusweb.service.taxonomy.ResearchQueryAnchorService.QueryAnchors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 代表性 hybrid 召回 query 手工验证用例，覆盖 ticker、公司、作者、章节词、指标词和宽泛主题。
 * @Logic: 使用真实 filter 构建器和业务 boost 排序器模拟候选，验证强过滤表达式和 soft boost 排序效果符合预期。
 * @Param: 无。
 * @Return: 无（仅断言代表性 query 的召回策略效果）。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
class RepresentativeHybridRetrievalManualValidationTests {

    /**
     * @Description: 验证代表性 query 的 hard filter 和业务 boost。
     * @Logic: 每类 query 都提供一个正确候选和一个干扰候选，正确候选应在业务加权后排第一。
     * @Param: 无。
     * @Return: 无（仅断言六类 query 验证结果）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Test
    void shouldValidateRepresentativeHybridRetrievalQueries() {
        // 验证 ticker query 会下推 ticker hard filter，并提升命中股票代码的候选。
        validateCase(
                "分析 300750.SZ 的盈利预测和估值弹性",
                anchors(List.of("BATTERY"), List.of(), List.of("宁德时代"), List.of("300750.SZ"), List.of(), List.of("VALUATION")),
                "ticker == \"300750.SZ\"",
                matched("ticker-hit", Map.of("ticker", "300750.SZ", "companyName", "宁德时代", "sectionPath", "盈利预测与估值")),
                matched("ticker-miss", Map.of("ticker", "600519.SH", "companyName", "贵州茅台", "sectionPath", "盈利预测"))
        );
        // 验证公司名 query 会下推 company hard filter，并提升命中公司名的候选。
        validateCase(
                "宁德时代储能业务竞争优势如何",
                anchors(List.of("STORAGE"), List.of(), List.of("宁德时代"), List.of(), List.of(), List.of()),
                "companyName == \"宁德时代\"",
                matched("company-hit", Map.of("companyName", "宁德时代", "themeCode", "STORAGE")),
                matched("company-miss", Map.of("companyName", "阳光电源", "themeCode", "STORAGE"))
        );
        // 验证作者 query 会生成 authorText OR/like hard filter，并提升作者命中的候选。
        validateCase(
                "作者张三对储能板块怎么看",
                anchors(List.of("STORAGE"), List.of(), List.of(), List.of(), List.of("张三"), List.of()),
                "authorText like \"%|张三|%\"",
                matched("author-hit", Map.of("authorText", "|张三|李四|", "themeCode", "STORAGE")),
                matched("author-miss", Map.of("authorText", "|王五|", "themeCode", "STORAGE"))
        );
        // 验证章节词 query 不默认下推宽泛主题，但会提升风险章节候选。
        validateCase(
                "储能系统风险提示有哪些",
                anchors(List.of("STORAGE"), List.of(), List.of(), List.of(), List.of(), List.of("RISK")),
                "chunkType == \"CHILD\"",
                matched("risk-hit", Map.of("sectionPath", "风险提示", "themeCode", "STORAGE")),
                matched("risk-miss", Map.of("sectionPath", "公司概况", "themeCode", "AI"))
        );
        // 验证指标词 query 会提升盈利预测、财务指标或估值章节候选。
        validateCase(
                "这家公司 PE 和盈利预测是否改善",
                anchors(List.of(), List.of(), List.of(), List.of(), List.of(), List.of("VALUATION")),
                "chunkType == \"CHILD\"",
                matched("metric-hit", Map.of("sectionPath", "盈利预测和财务指标", "themeCode", "BATTERY")),
                matched("metric-miss", Map.of("sectionPath", "投资要点", "themeCode", "BATTERY"))
        );
        // 验证宽泛主题 query 默认不把主题下推 hard filter，但会通过 soft boost 提升主题命中候选。
        validateCase(
                "储能产业链未来一年景气度如何",
                anchors(List.of("STORAGE"), List.of(), List.of(), List.of(), List.of(), List.of()),
                "chunkType == \"CHILD\"",
                matched("theme-hit", Map.of("reportThemeCode", "STORAGE", "themeCode", "STORAGE")),
                matched("theme-miss", Map.of("reportThemeCode", "AI", "themeCode", "AI"))
        );
    }

    /**
     * @Description: 验证单个代表性 query。
     * @Logic: 检查 Milvus filter 包含预期表达式，并确认业务 boost 后命中候选排在干扰候选前。
     * @Param: query 代表性 query；anchors query 锚点；expectedFilterFragment 预期 filter 片段；matched 正确候选；distractor 干扰候选。
     * @Return: 无（仅断言单个 query 验证结果）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private void validateCase(String query,
                              QueryAnchors anchors,
                              String expectedFilterFragment,
                              RetrievedChunk matched,
                              RetrievedChunk distractor) {
        // 使用真实请求构建器生成 Milvus filter。
        ReportHybridSearchRequest request = new RetrievalSearchRequestBuilder().build(query, 20, anchors);
        // 断言 filter 包含该类 query 预期的 hard filter 片段。
        Assertions.assertTrue(request.milvusFilter().contains(expectedFilterFragment), query);
        // 使用真实业务 boost 排序器处理模拟候选。
        List<RetrievedChunk> ranked = new RetrievalBusinessBoostRanker().rank(List.of(distractor, matched), anchors);
        // 命中候选必须排到第一，代表该 query 类型的软信号能纠正同分候选顺序。
        Assertions.assertEquals(matched.document().getId(), ranked.get(0).document().getId(), query);
        // 排名第一的候选必须写入 normalizedScore 诊断字段。
        Assertions.assertTrue(ranked.get(0).document().getMetadata().containsKey("normalizedScore"), query);
        // 排名第一的候选必须写入 businessBoostScore 诊断字段。
        Assertions.assertTrue(ranked.get(0).document().getMetadata().containsKey("businessBoostScore"), query);
        // 排名第一的候选必须写入 finalScore 诊断字段。
        Assertions.assertTrue(ranked.get(0).document().getMetadata().containsKey("finalScore"), query);
    }

    /**
     * @Description: 构造 query 锚点。
     * @Logic: 测试只关心检索策略字段，matchedTerms 使用空列表即可。
     * @Param: themeCodes 主题编码；industryCodes 行业编码；companyCodes 公司名；tickers 股票代码；authorNames 作者名；sectionIntents 章节意图。
     * @Return: query 锚点。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private QueryAnchors anchors(List<String> themeCodes,
                                 List<String> industryCodes,
                                 List<String> companyCodes,
                                 List<String> tickers,
                                 List<String> authorNames,
                                 List<String> sectionIntents) {
        // 返回完整 QueryAnchors，matchedTerms 在本验证中不参与排序。
        return new QueryAnchors(themeCodes, industryCodes, companyCodes, tickers, authorNames, sectionIntents, List.of());
    }

    /**
     * @Description: 构造模拟召回候选。
     * @Logic: metadata 使用 HashMap，便于排序器写入诊断分数。
     * @Param: id 文档 ID；metadata 候选 metadata。
     * @Return: 召回候选。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private RetrievedChunk matched(String id, Map<String, String> metadata) {
        // 复制 metadata 到可变 Map。
        Map<String, Object> mutableMetadata = new HashMap<>(metadata);
        // 构造文档文本。
        String text = "代表性 query 验证候选：" + id;
        // 构造召回候选并给定相同初始分数，确保排序差异来自业务 boost。
        return new RetrievedChunk(new Document(id, text, mutableMetadata), 0.5D, text, text);
    }
}
