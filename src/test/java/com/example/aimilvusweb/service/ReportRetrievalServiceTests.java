package com.example.aimilvusweb.service;

import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportDocumentTagMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportRetrievalServiceTests类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
class ReportRetrievalServiceTests {

    @Test
    void shouldDeduplicateAndExpandParentContext() {
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        ReportChunkMapper reportChunkMapper = mock(ReportChunkMapper.class);
        ReportQualityProperties properties = new ReportQualityProperties();
        properties.getRetrieval().setInitialTopK(10);
        properties.getRetrieval().setFinalTopK(2);
        ReportRetrievalService service = new ReportRetrievalService(vectorStoreProvider, reportChunkMapper, properties);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("child one", Map.of("chunkUid", "c1", "parentChunkUid", "p1", "score", 0.9D)),
                new Document("child duplicate", Map.of("chunkUid", "c1", "parentChunkUid", "p1", "score", 0.8D)),
                new Document("child two", Map.of("chunkUid", "c2", "parentChunkUid", "", "score", 0.7D))
        ));
        ReportChunk parent = new ReportChunk();
        parent.setChunkText("parent evidence");
        when(reportChunkMapper.selectByChunkUid("p1")).thenReturn(parent);

        List<ReportRetrievalService.RetrievedChunk> chunks = service.retrieve("需求改善");

        Assertions.assertEquals(2, chunks.size());
        Assertions.assertEquals("parent evidence", chunks.get(0).evidenceText());
        Assertions.assertEquals("child two", chunks.get(1).evidenceText());
    }

    @Test
    void shouldDeduplicateSameSliceTextWhenChunkUidIsMissing() {
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        ReportChunkMapper reportChunkMapper = mock(ReportChunkMapper.class);
        ReportQualityProperties properties = new ReportQualityProperties();
        properties.getRetrieval().setInitialTopK(10);
        properties.getRetrieval().setFinalTopK(5);
        ReportRetrievalService service = new ReportRetrievalService(vectorStoreProvider, reportChunkMapper, properties);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("Q1业绩：收入同比增长，利润率改善。", Map.of(
                        "title", "公司财报点评", "source", "券商研报", "sectionPath", "Q1业绩", "score", 0.92D)),
                new Document("Q1业绩：收入同比增长，利润率改善。", Map.of(
                        "title", "公司财报点评", "source", "券商研报", "sectionPath", "Q1业绩", "score", 0.91D)),
                new Document("Q1业绩：经营现金流同步改善。", Map.of(
                        "title", "公司财报点评", "source", "券商研报", "sectionPath", "Q1业绩", "score", 0.86D))
        ));

        List<ReportRetrievalService.RetrievedChunk> chunks = service.retrieve("Q1业绩");

        Assertions.assertEquals(2, chunks.size());
        Assertions.assertEquals("Q1业绩：收入同比增长，利润率改善。", chunks.get(0).chunkText());
        Assertions.assertEquals("Q1业绩：经营现金流同步改善。", chunks.get(1).chunkText());
    }

    @Test
    void shouldFailWhenVectorStoreIsMissing() {
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        ReportChunkMapper reportChunkMapper = mock(ReportChunkMapper.class);
        ReportRetrievalService service = new ReportRetrievalService(vectorStoreProvider, reportChunkMapper, new ReportQualityProperties());

        when(vectorStoreProvider.getIfAvailable()).thenReturn(null);

        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> service.retrieve("query"));
        Assertions.assertTrue(exception.getMessage().contains("VectorStore is not configured"));
    }

    @Test
    void shouldBuildMetadataFilterAndNormalizeDistanceScore() {
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        ReportChunkMapper reportChunkMapper = mock(ReportChunkMapper.class);
        ReportQualityProperties properties = new ReportQualityProperties();
        ResearchQueryAnchorService anchorService = mock(ResearchQueryAnchorService.class);
        com.example.aimilvusweb.repository.ReportChunkTagMapper tagMapper = mock(com.example.aimilvusweb.repository.ReportChunkTagMapper.class);
        ReportDocumentTagMapper documentTagMapper = mock(ReportDocumentTagMapper.class);
        ReportRetrievalService service = new ReportRetrievalService(vectorStoreProvider, reportChunkMapper, properties, anchorService, tagMapper, documentTagMapper);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(anchorService.extract("储能")).thenReturn(new ResearchQueryAnchorService.QueryAnchors(
                List.of("STORAGE"), List.of(), List.of(), List.of(), List.of(), List.of("储能")
        ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("storage evidence", Map.of("chunkUid", "c1", "parentChunkUid", "", "distance", 1.0D))
        ));

        List<ReportRetrievalService.RetrievedChunk> chunks = service.retrieve("储能");

        ArgumentCaptor<SearchRequest> captor = forClass(SearchRequest.class);
        org.mockito.Mockito.verify(vectorStore).similaritySearch(captor.capture());
        Assertions.assertTrue(captor.getValue().hasFilterExpression());
        Assertions.assertTrue(captor.getValue().getFilterExpression().toString().contains("reportThemeCode"));
        Assertions.assertEquals(0.5D, chunks.get(0).score());
    }

    @Test
    void shouldDiagnoseReportAndChunkTagsWhenFilteredSearchIsEmpty() {
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        ReportChunkMapper reportChunkMapper = mock(ReportChunkMapper.class);
        ReportQualityProperties properties = new ReportQualityProperties();
        ResearchQueryAnchorService anchorService = mock(ResearchQueryAnchorService.class);
        com.example.aimilvusweb.repository.ReportChunkTagMapper tagMapper = mock(com.example.aimilvusweb.repository.ReportChunkTagMapper.class);
        ReportDocumentTagMapper documentTagMapper = mock(ReportDocumentTagMapper.class);
        ReportRetrievalService service = new ReportRetrievalService(vectorStoreProvider, reportChunkMapper, properties, anchorService, tagMapper, documentTagMapper);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(anchorService.extract("储能")).thenReturn(new ResearchQueryAnchorService.QueryAnchors(
                List.of("STORAGE"), List.of(), List.of(), List.of(), List.of(), List.of("储能")
        ));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        List<ReportRetrievalService.RetrievedChunk> chunks = service.retrieve("储能");

        Assertions.assertTrue(chunks.isEmpty());
        verify(documentTagMapper).countByTagCodes("THEME", List.of("STORAGE"));
        verify(tagMapper).countByTagCodes("THEME", List.of("STORAGE"));
    }

    @Test
    void shouldAggregateChildrenByParentAndKeepHitDetails() {
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        ReportChunkMapper reportChunkMapper = mock(ReportChunkMapper.class);
        ReportQualityProperties properties = new ReportQualityProperties();
        properties.getRetrieval().setFinalTopK(5);
        ReportRetrievalService service = new ReportRetrievalService(vectorStoreProvider, reportChunkMapper, properties);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("child one", Map.of("chunkUid", "c1", "parentChunkUid", "p1", "score", 0.91D)),
                new Document("child two", Map.of("chunkUid", "c2", "parentChunkUid", "p1", "score", 0.88D))
        ));
        ReportChunk parent = chunk("p1", "parent context for summary", 0);
        when(reportChunkMapper.selectByChunkUid("p1")).thenReturn(parent);

        List<ReportRetrievalService.RetrievedChunk> chunks = service.retrieve("储能推荐理由");

        Assertions.assertEquals(1, chunks.size());
        Assertions.assertEquals("parent context for summary", chunks.get(0).evidenceText());
        Assertions.assertEquals(2, chunks.get(0).hitCount());
        Assertions.assertEquals(2, chunks.get(0).hitChildren().size());
        Assertions.assertEquals(ReportRetrievalService.EvidenceContextType.FULL_PARENT, chunks.get(0).contextType());
    }

    @Test
    void shouldPreferParentWithMoreHitsBeforeSingleHigherScoreHit() {
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        ReportChunkMapper reportChunkMapper = mock(ReportChunkMapper.class);
        ReportQualityProperties properties = new ReportQualityProperties();
        properties.getRetrieval().setFinalTopK(3);
        ReportRetrievalService service = new ReportRetrievalService(vectorStoreProvider, reportChunkMapper, properties);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("single high score", Map.of("chunkUid", "b1", "parentChunkUid", "pb", "score", 0.95D)),
                new Document("group hit one", Map.of("chunkUid", "a1", "parentChunkUid", "pa", "score", 0.90D)),
                new Document("group hit two", Map.of("chunkUid", "a2", "parentChunkUid", "pa", "score", 0.89D))
        ));
        when(reportChunkMapper.selectByChunkUid("pa")).thenReturn(chunk("pa", "parent A context", 0));
        when(reportChunkMapper.selectByChunkUid("pb")).thenReturn(chunk("pb", "parent B context", 0));

        List<ReportRetrievalService.RetrievedChunk> chunks = service.retrieve("推荐理由");

        Assertions.assertEquals("pa", chunks.get(0).document().getMetadata().get("parentChunkUid"));
        Assertions.assertEquals(2, chunks.get(0).hitCount());
        Assertions.assertEquals(0.90D, chunks.get(0).maxScore());
    }

    @Test
    void shouldRespectParentGroupLimitAndEvidenceBudget() {
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        ReportChunkMapper reportChunkMapper = mock(ReportChunkMapper.class);
        ReportQualityProperties properties = new ReportQualityProperties();
        properties.getRetrieval().setFinalTopK(5);
        properties.getRetrieval().setMaxParentEvidenceGroups(1);
        properties.getRetrieval().setTotalEvidenceContextTokens(20);
        ReportRetrievalService service = new ReportRetrievalService(vectorStoreProvider, reportChunkMapper, properties);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("first parent child", Map.of("chunkUid", "a1", "parentChunkUid", "pa", "score", 0.92D)),
                new Document("second parent child", Map.of("chunkUid", "b1", "parentChunkUid", "pb", "score", 0.91D))
        ));
        when(reportChunkMapper.selectByChunkUid("pa")).thenReturn(chunk("pa", "parent A budget context", 0));
        when(reportChunkMapper.selectByChunkUid("pb")).thenReturn(chunk("pb", "parent B budget context", 0));

        List<ReportRetrievalService.RetrievedChunk> chunks = service.retrieve("预算控制");

        Assertions.assertEquals(1, chunks.size());
        Assertions.assertEquals("pa", chunks.get(0).document().getMetadata().get("parentChunkUid"));
    }

    @Test
    void shouldUseChildWindowForLargeParentAndKeepChildReferences() {
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        ReportChunkMapper reportChunkMapper = mock(ReportChunkMapper.class);
        ReportQualityProperties properties = new ReportQualityProperties();
        properties.getRetrieval().setLargeParentContextTokens(10);
        properties.getRetrieval().setMaxParentContextTokens(200);
        properties.getRetrieval().setChildWindowNeighborCount(1);
        ReportRetrievalService service = new ReportRetrievalService(vectorStoreProvider, reportChunkMapper, properties);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("hit child", Map.of("chunkUid", "c2", "parentChunkUid", "p1", "score", 0.93D))
        ));
        when(reportChunkMapper.selectByChunkUid("p1")).thenReturn(chunk("p1", "这是一个超过阈值的父切片上下文，包含较多总结型分析内容。", 0));
        when(reportChunkMapper.selectChildrenByParentChunkUid("p1")).thenReturn(List.of(
                chunk("c1", "previous sibling", 0),
                chunk("c2", "hit child", 1),
                chunk("c3", "next sibling", 2)
        ));

        List<ReportRetrievalService.RetrievedChunk> chunks = service.retrieve("大父切片");

        Assertions.assertEquals(1, chunks.size());
        Assertions.assertEquals(ReportRetrievalService.EvidenceContextType.CHILD_WINDOW, chunks.get(0).contextType());
        Assertions.assertTrue(chunks.get(0).evidenceText().contains("previous sibling"));
        Assertions.assertTrue(chunks.get(0).evidenceText().contains("hit child"));
        Assertions.assertTrue(chunks.get(0).evidenceText().contains("next sibling"));
        Assertions.assertEquals("c2", chunks.get(0).hitChildren().get(0).document().getMetadata().get("chunkUid"));
    }

    @Test
    void shouldFallbackToChildWhenParentIsMissing() {
        VectorStore vectorStore = mock(VectorStore.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        ReportChunkMapper reportChunkMapper = mock(ReportChunkMapper.class);
        ReportQualityProperties properties = new ReportQualityProperties();
        ReportRetrievalService service = new ReportRetrievalService(vectorStoreProvider, reportChunkMapper, properties);

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("orphan child evidence", Map.of("chunkUid", "c1", "parentChunkUid", "missing-parent", "score", 0.87D))
        ));
        when(reportChunkMapper.selectByChunkUid("missing-parent")).thenReturn(null);

        List<ReportRetrievalService.RetrievedChunk> chunks = service.retrieve("父切片缺失");

        Assertions.assertEquals(1, chunks.size());
        Assertions.assertEquals("orphan child evidence", chunks.get(0).evidenceText());
        Assertions.assertEquals(ReportRetrievalService.EvidenceContextType.CHILD_FALLBACK, chunks.get(0).contextType());
        Assertions.assertEquals(1, chunks.get(0).hitCount());
    }

    private ReportChunk chunk(String chunkUid, String text, int index) {
        ReportChunk chunk = new ReportChunk();
        chunk.setChunkUid(chunkUid);
        chunk.setChunkText(text);
        chunk.setChunkIndex(index);
        return chunk;
    }
}
