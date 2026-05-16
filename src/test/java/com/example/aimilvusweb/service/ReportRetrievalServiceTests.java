package com.example.aimilvusweb.service;

import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void shouldFailWhenVectorStoreIsMissing() {
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        ReportChunkMapper reportChunkMapper = mock(ReportChunkMapper.class);
        ReportRetrievalService service = new ReportRetrievalService(vectorStoreProvider, reportChunkMapper, new ReportQualityProperties());

        when(vectorStoreProvider.getIfAvailable()).thenReturn(null);

        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> service.retrieve("query"));
        Assertions.assertTrue(exception.getMessage().contains("VectorStore is not configured"));
    }
}
