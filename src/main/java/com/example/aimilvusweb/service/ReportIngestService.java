package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.util.PdfUtils;
import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import com.example.aimilvusweb.dto.ReportUploadRespDTO;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportDocument;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReportIngestService {

    private final ReportDocumentMapper reportDocumentMapper;
    private final ReportChunkMapper reportChunkMapper;
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public ReportIngestService(ReportDocumentMapper reportDocumentMapper,
                               ReportChunkMapper reportChunkMapper,
                               ObjectProvider<VectorStore> vectorStoreProvider) {
        this.reportDocumentMapper = reportDocumentMapper;
        this.reportChunkMapper = reportChunkMapper;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    @Transactional
    public ReportUploadRespDTO ingest(MultipartFile file, String title, String source, String institution, LocalDate publishDate) {
        VectorStore vectorStore = requireVectorStore();
        validateFile(file);
        List<String> chunks = parseAndChunk(file);
        ReportDocument report = persistReportDocument(file, title, source, institution, publishDate);
        List<ReportChunk> persistedChunks = persistChunks(report, chunks);
        vectorStore.add(buildVectorDocuments(report, persistedChunks));
        return buildUploadResp(report, persistedChunks.size());
    }

    private VectorStore requireVectorStore() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new IllegalStateException("VectorStore is not configured. Set spring.ai.vectorstore.type=milvus and Milvus properties.");
        }
        return vectorStore;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("PDF file is required");
        }
    }

    private List<String> parseAndChunk(MultipartFile file) {
        String parsedText = PdfUtils.extractText(file);
        List<String> chunks = SemanticChunkUtils.chunkByParagraphWindow(parsedText, 3, 1);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("No valid chunks generated from PDF");
        }
        return chunks;
    }

    private ReportDocument persistReportDocument(MultipartFile file, String title, String source, String institution, LocalDate publishDate) {
        ReportDocument report = new ReportDocument();
        report.setTitle((title == null || title.isBlank()) ? file.getOriginalFilename() : title.trim());
        report.setSource((source == null || source.isBlank()) ? "uploaded" : source.trim());
        report.setInstitution(institution == null ? null : institution.trim());
        report.setPublishDate(publishDate);
        report.setCreatedAt(Instant.now());
        reportDocumentMapper.insert(report);
        return report;
    }

    private List<ReportChunk> persistChunks(ReportDocument report, List<String> chunks) {
        List<ReportChunk> persistedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ReportChunk chunk = new ReportChunk();
            chunk.setReportId(report.getId());
            chunk.setChunkIndex(i);
            chunk.setChunkUid(UUID.randomUUID().toString().replace("-", ""));
            chunk.setChunkText(chunks.get(i));
            chunk.setPageNumber(0);
            chunk.setCreatedAt(Instant.now());
            reportChunkMapper.insert(chunk);
            persistedChunks.add(chunk);
        }
        return persistedChunks;
    }

    private List<Document> buildVectorDocuments(ReportDocument report, List<ReportChunk> chunks) {
        List<Document> vectorDocuments = new ArrayList<>();
        for (ReportChunk chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("reportId", report.getId());
            metadata.put("chunkId", chunk.getId());
            metadata.put("chunkUid", chunk.getChunkUid());
            metadata.put("chunkIndex", chunk.getChunkIndex());
            metadata.put("title", report.getTitle());
            metadata.put("source", report.getSource());
            metadata.put("institution", report.getInstitution() == null ? "" : report.getInstitution());
            metadata.put("publishDate", report.getPublishDate() == null ? "" : report.getPublishDate().toString());
            vectorDocuments.add(new Document(chunk.getChunkText(), metadata));
        }
        return vectorDocuments;
    }

    private ReportUploadRespDTO buildUploadResp(ReportDocument report, int chunkCount) {
        return new ReportUploadRespDTO(report.getId(), report.getTitle(), chunkCount, "Upload and ingest completed");
    }
}
