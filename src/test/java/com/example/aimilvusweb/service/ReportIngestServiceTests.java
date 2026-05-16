package com.example.aimilvusweb.service;

import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.repository.ReportChunkDiagnosticMapper;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import com.example.aimilvusweb.repository.ReportOcrPageMapper;
import com.example.aimilvusweb.repository.ReportParagraphAtomMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportIngestServiceTests {

    @Test
    void shouldNotPersistChunksOrVectorsWhenOcrFails() {
        ReportDocumentMapper documentMapper = mock(ReportDocumentMapper.class);
        ReportChunkMapper chunkMapper = mock(ReportChunkMapper.class);
        ReportOcrPageMapper ocrPageMapper = mock(ReportOcrPageMapper.class);
        ReportParagraphAtomMapper paragraphAtomMapper = mock(ReportParagraphAtomMapper.class);
        ReportChunkDiagnosticMapper chunkDiagnosticMapper = mock(ReportChunkDiagnosticMapper.class);
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ReportOcrParseService ocrParseService = mock(ReportOcrParseService.class);
        ReportSemanticChunkService semanticChunkService = mock(ReportSemanticChunkService.class);
        ReportIngestFailureService failureService = mock(ReportIngestFailureService.class);
        ReportIngestService ingestService = new ReportIngestService(
                documentMapper,
                chunkMapper,
                ocrPageMapper,
                paragraphAtomMapper,
                chunkDiagnosticMapper,
                vectorStoreProvider,
                ocrParseService,
                semanticChunkService,
                failureService,
                new ReportQualityProperties()
        );
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "demo".getBytes());

        when(vectorStoreProvider.getIfAvailable()).thenReturn(vectorStore);
        when(ocrParseService.parseDetailed(file)).thenThrow(new IllegalArgumentException("OCR recognized no usable report text"));

        IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ingestService.ingest(file, "title", "source", "institution", null)
        );

        Assertions.assertTrue(exception.getMessage().contains("OCR recognized no usable report text"));
        verify(documentMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(chunkMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(vectorStore, never()).add(org.mockito.ArgumentMatchers.anyList());
    }
}
