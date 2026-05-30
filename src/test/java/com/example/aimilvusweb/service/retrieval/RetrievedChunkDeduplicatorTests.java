package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.ReportRetrievalService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

/**
 * @Description: RetrievedChunkDeduplicator 单元测试，验证召回候选稳定去重行为。
 * @Logic: 分别构造 identity key 重复和 content key 重复的候选，断言去重保留首次出现项并保持顺序。
 * @Param: 无。
 * @Return: 无（测试类）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
class RetrievedChunkDeduplicatorTests {

    /**
     * @Description: 验证相同 chunkUid 的候选只保留首次出现项。
     * @Logic: 两条候选共享 chunkUid，去重后只保留第一条，并继续保留后续不同 chunkUid 候选。
     * @Param: 无。
     * @Return: 无（断言去重结果）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    @Test
    void shouldDeduplicateByChunkUidAndKeepFirstCandidate() {
        RetrievedChunkDeduplicator deduplicator = new RetrievedChunkDeduplicator();

        List<ReportRetrievalService.RetrievedChunk> deduplicated = deduplicator.deduplicate(List.of(
                chunk("first", Map.of("chunkUid", "c1")),
                chunk("duplicate", Map.of("chunkUid", "c1")),
                chunk("second", Map.of("chunkUid", "c2"))
        ));

        Assertions.assertEquals(2, deduplicated.size());
        Assertions.assertEquals("first", deduplicated.get(0).chunkText());
        Assertions.assertEquals("second", deduplicated.get(1).chunkText());
    }

    /**
     * @Description: 验证 metadata 缺失时按展示内容去重。
     * @Logic: 两条候选标题、来源、章节和正文相同但无 chunkUid，去重后只保留首次出现项。
     * @Param: 无。
     * @Return: 无（断言去重结果）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    @Test
    void shouldDeduplicateByContentWhenIdentityIsMissing() {
        RetrievedChunkDeduplicator deduplicator = new RetrievedChunkDeduplicator();

        List<ReportRetrievalService.RetrievedChunk> deduplicated = deduplicator.deduplicate(List.of(
                chunk("同一段正文", Map.of("title", "研报", "source", "券商", "sectionPath", "正文")),
                chunk("同一段正文", Map.of("title", "研报", "source", "券商", "sectionPath", "正文")),
                chunk("另一段正文", Map.of("title", "研报", "source", "券商", "sectionPath", "正文"))
        ));

        Assertions.assertEquals(2, deduplicated.size());
        Assertions.assertEquals("同一段正文", deduplicated.get(0).chunkText());
        Assertions.assertEquals("另一段正文", deduplicated.get(1).chunkText());
    }

    /**
     * @Description: 构造测试召回候选。
     * @Logic: 用文本和 metadata 创建 Document，并包装为 RetrievedChunk。
     * @Param: text 候选文本；metadata 候选 metadata。
     * @Return: 测试用 RetrievedChunk。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private ReportRetrievalService.RetrievedChunk chunk(String text, Map<String, Object> metadata) {
        return new ReportRetrievalService.RetrievedChunk(new Document(text, metadata), 0.9D, text, text);
    }
}
