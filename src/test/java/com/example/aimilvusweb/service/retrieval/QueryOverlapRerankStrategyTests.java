package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.retrieval.ReportRetrievalService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

/**
 * @Description: QueryOverlapRerankStrategy 单元测试，验证 query overlap 重排语义。
 * @Logic: 构造不同字符覆盖度的候选，断言 overlap 分数高的候选排序靠前，分数相同时保持稳定顺序。
 * @Param: 无。
 * @Return: 无（测试类）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
class QueryOverlapRerankStrategyTests {

    /**
     * @Description: 验证 overlap 分数高的候选优先。
     * @Logic: query 包含“储能风险”，候选文本覆盖字符越多越靠前。
     * @Param: 无。
     * @Return: 无（断言重排结果）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    @Test
    void shouldSortCandidatesByQueryOverlapDescending() {
        QueryOverlapRerankStrategy strategy = new QueryOverlapRerankStrategy();

        List<RetrievedChunk> reranked = strategy.rerank("储能风险", List.of(
                chunk("港口吞吐量改善"),
                chunk("储能系统需求改善"),
                chunk("储能风险包括价格波动")
        ));

        Assertions.assertEquals("储能风险包括价格波动", reranked.get(0).chunkText());
        Assertions.assertEquals("储能系统需求改善", reranked.get(1).chunkText());
        Assertions.assertEquals("港口吞吐量改善", reranked.get(2).chunkText());
    }

    /**
     * @Description: 验证相同 overlap 分数时保持原顺序。
     * @Logic: 两个候选与 query 重合度相同，stream sorted 稳定排序应保留输入先后。
     * @Param: 无。
     * @Return: 无（断言稳定排序）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    @Test
    void shouldKeepStableOrderWhenOverlapScoresTie() {
        QueryOverlapRerankStrategy strategy = new QueryOverlapRerankStrategy();

        List<RetrievedChunk> reranked = strategy.rerank("abc", List.of(
                chunk("a"),
                chunk("b")
        ));

        Assertions.assertEquals("a", reranked.get(0).chunkText());
        Assertions.assertEquals("b", reranked.get(1).chunkText());
    }

    /**
     * @Description: 构造测试召回候选。
     * @Logic: 用文本构造最小 Document 和 RetrievedChunk，metadata 仅保留 chunkUid。
     * @Param: text 候选文本。
     * @Return: 测试用 RetrievedChunk。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private RetrievedChunk chunk(String text) {
        return new RetrievedChunk(new Document(text, Map.of("chunkUid", text)), 0.9D, text, text);
    }
}
