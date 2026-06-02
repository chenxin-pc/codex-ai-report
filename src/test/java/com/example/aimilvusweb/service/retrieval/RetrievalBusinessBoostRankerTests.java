package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.ReportRetrievalService.RetrievedChunk;
import com.example.aimilvusweb.service.ResearchQueryAnchorService.QueryAnchors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

/**
 * @Description: RetrievalBusinessBoostRanker 单元测试，验证业务 metadata boost 和诊断分数写入。
 * @Logic: 构造两个同分候选，命中作者和主题的候选应排在前面并写入 normalized/business/final score。
 * @Param: 无。
 * @Return: 无（仅断言排序和 metadata）。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
class RetrievalBusinessBoostRankerTests {

    /**
     * @Description: 验证业务锚点命中会提升候选排序。
     * @Logic: 两个候选原始分相同，作者和主题命中的候选获得更高 finalScore。
     * @Param: 无。
     * @Return: 无（仅断言排序结果）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Test
    void shouldBoostCandidateWhenMetadataMatchesAnchors() {
        // 创建业务加权组件。
        RetrievalBusinessBoostRanker ranker = new RetrievalBusinessBoostRanker();
        // 构造命中作者和主题的候选。
        RetrievedChunk matched = new RetrievedChunk(new Document("matched", "命中候选", new java.util.HashMap<>(Map.of(
                "authorText", "|张三|",
                "themeCode", "STORAGE",
                "sectionPath", "风险提示"
        ))), 0.5D, "命中候选", "命中候选");
        // 构造未命中 metadata 的候选。
        RetrievedChunk unmatched = new RetrievedChunk(new Document("unmatched", "普通候选", new java.util.HashMap<>(Map.of(
                "authorText", "|李四|",
                "themeCode", "AI",
                "sectionPath", "正文"
        ))), 0.5D, "普通候选", "普通候选");
        // 构造作者、主题和风险章节锚点。
        QueryAnchors anchors = new QueryAnchors(List.of("STORAGE"), List.of(), List.of(), List.of(), List.of("张三"), List.of("RISK"), List.of("储能"));

        // 执行业务加权排序。
        List<RetrievedChunk> ranked = ranker.rank(List.of(unmatched, matched), anchors);

        // 命中候选排到第一。
        Assertions.assertEquals("matched", ranked.get(0).document().getId());
        // normalizedScore 被写入诊断字段。
        Assertions.assertEquals(0.5D, ranked.get(0).document().getMetadata().get("normalizedScore"));
        // businessBoostScore 大于 0。
        Assertions.assertTrue((Double) ranked.get(0).document().getMetadata().get("businessBoostScore") > 0D);
        // finalScore 大于原始分。
        Assertions.assertTrue((Double) ranked.get(0).document().getMetadata().get("finalScore") > 0.5D);
    }
}
