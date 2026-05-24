package com.example.aimilvusweb.service;

import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.enums.QueryIntentEnum;
import com.example.aimilvusweb.enums.RecommendationOutputLevelEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

/**
 * @Description: RecommendationEvidenceGuardrailService 测试，验证召回证据质量降级依据。
 * @Logic: 构造不同召回片段，覆盖无证据、低相关、多主体冲突和主题候选输出路径。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-24 00:45:00
 */
class RecommendationEvidenceGuardrailServiceTests {

    @Test
    void shouldDowngradeWhenEvidenceIsMissing() {
        RecommendationEvidenceGuardrailService service = buildService();

        RecommendationEvidenceGuardrailService.EvidenceDecision decision = service.evaluate("分析000582", QueryIntentEnum.ANALYZE, List.of());

        Assertions.assertEquals(RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED, decision.outputLevel());
        Assertions.assertTrue(decision.degradationReasons().contains("EVIDENCE_MISSING"));
    }

    @Test
    void shouldDowngradeWhenEvidenceIsLowRelevant() {
        RecommendationEvidenceGuardrailService service = buildService();

        RecommendationEvidenceGuardrailService.EvidenceDecision decision = service.evaluate(
                "港口行业",
                QueryIntentEnum.ANALYZE,
                List.of(chunk("医药研发", "NL003 三期临床 北交所 创新药"))
        );

        Assertions.assertEquals(RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED, decision.outputLevel());
        Assertions.assertTrue(decision.degradationReasons().contains("LOW_QUERY_RELEVANCE"));
    }

    @Test
    void shouldDowngradeDirectAnalysisWhenEntitiesConflict() {
        RecommendationEvidenceGuardrailService service = buildService();

        RecommendationEvidenceGuardrailService.EvidenceDecision decision = service.evaluate(
                "北部湾港",
                QueryIntentEnum.ANALYZE,
                List.of(
                        chunk("北部湾港点评", "北部湾港 000582.SZ 港口 西部陆海新通道"),
                        chunk("北交所医药更新", "NL003 三期临床 医药 北交所 总市值 86亿元")
                )
        );

        Assertions.assertEquals(RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED, decision.outputLevel());
        Assertions.assertTrue(decision.degradationReasons().contains("ENTITY_CONFLICT"));
        Assertions.assertTrue(decision.degradationReasons().contains("DATA_CONFLICT"));
    }

    @Test
    void shouldAllowThemeResearchWithCompanyCandidates() {
        RecommendationEvidenceGuardrailService service = buildService();

        RecommendationEvidenceGuardrailService.EvidenceDecision decision = service.evaluate(
                "港口行业",
                QueryIntentEnum.THEME_RESEARCH,
                List.of(
                        chunk("北部湾港点评", "北部湾港 000582.SZ 港口 西部陆海新通道"),
                        chunk("港口行业观察", "港口 吞吐量 政策 景气度")
                )
        );

        Assertions.assertEquals(RecommendationOutputLevelEnum.L2_THEME_RESEARCH, decision.outputLevel());
        Assertions.assertTrue(decision.quality().entityConsistent());
    }

    private RecommendationEvidenceGuardrailService buildService() {
        ReportQualityProperties properties = new ReportQualityProperties();
        QueryGuardrailDictionaryService dictionaryService = new QueryGuardrailDictionaryService(new DefaultResourceLoader(), properties);
        return new RecommendationEvidenceGuardrailService(dictionaryService, properties);
    }

    private ReportRetrievalService.RetrievedChunk chunk(String title, String text) {
        Document document = new Document(text, Map.of("title", title, "source", "券商研报", "sectionPath", "投资要点"));
        return new ReportRetrievalService.RetrievedChunk(document, 0.9D, text, text);
    }
}
