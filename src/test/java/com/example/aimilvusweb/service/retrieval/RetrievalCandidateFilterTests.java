package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.service.ReportRetrievalService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

/**
 * @Description: RetrievalCandidateFilter 单元测试，验证召回候选分数阈值过滤语义。
 * @Logic: 构造不同分数的 RetrievedChunk，检查阈值关闭、阈值开启和缺失分数时的保留/过滤行为。
 * @Param: 无。
 * @Return: 无（测试类）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
class RetrievalCandidateFilterTests {

    /**
     * @Description: 验证阈值开启时过滤低分候选。
     * @Logic: 设置 minSimilarityScore=0.8，仅低于阈值且分数非空的候选被丢弃，缺失分数候选保留。
     * @Param: 无。
     * @Return: 无（断言过滤结果）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    @Test
    void shouldFilterOnlyScoredCandidatesBelowThreshold() {
        ReportQualityProperties properties = new ReportQualityProperties();
        properties.getRetrieval().setMinSimilarityScore(0.8D);
        RetrievalCandidateFilter filter = new RetrievalCandidateFilter(properties);

        List<ReportRetrievalService.RetrievedChunk> filtered = filter.filter(List.of(
                chunk("high", 0.9D),
                chunk("low", 0.7D),
                chunk("missing", null)
        ));

        Assertions.assertEquals(2, filtered.size());
        Assertions.assertEquals("high", filtered.get(0).chunkText());
        Assertions.assertEquals("missing", filtered.get(1).chunkText());
    }

    /**
     * @Description: 验证阈值关闭时保留全部候选。
     * @Logic: 默认 minSimilarityScore=0，不应过滤低分候选，保持候选列表原顺序。
     * @Param: 无。
     * @Return: 无（断言过滤结果）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    @Test
    void shouldKeepAllCandidatesWhenThresholdDisabled() {
        RetrievalCandidateFilter filter = new RetrievalCandidateFilter(new ReportQualityProperties());

        List<ReportRetrievalService.RetrievedChunk> filtered = filter.filter(List.of(
                chunk("first", 0.1D),
                chunk("second", 0.2D)
        ));

        Assertions.assertEquals(2, filtered.size());
        Assertions.assertEquals("first", filtered.get(0).chunkText());
        Assertions.assertEquals("second", filtered.get(1).chunkText());
    }

    /**
     * @Description: 构造测试召回候选。
     * @Logic: 使用 chunkText 和 score 构造最小 RetrievedChunk，metadata 只保留 chunkUid 便于定位。
     * @Param: text 候选文本；score 相关性分数。
     * @Return: 测试用 RetrievedChunk。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private ReportRetrievalService.RetrievedChunk chunk(String text, Double score) {
        return new ReportRetrievalService.RetrievedChunk(new Document(text, Map.of("chunkUid", text)), score, text, text);
    }
}
