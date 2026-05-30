package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportDocument;
import com.example.aimilvusweb.service.ReportVectorMetadataSyncJobService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 入库向量文档构建器，负责将 CHILD chunk 转换为 Spring AI Document。
 * @Logic: 结构化 metadata 服务可用时优先使用增强 metadata；不可用时回退默认 report/chunk metadata 与标签占位字段。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Component
public class ReportVectorDocumentBuilder {

    /** metadata 同步服务提供器，用于可选增强 Milvus Document metadata。 */
    private final ObjectProvider<ReportVectorMetadataSyncJobService> metadataSyncJobServiceProvider;

    /**
     * @Description: 初始化向量文档构建器。
     * @Logic: 保存 metadata 服务提供器，构建时按可用性选择增强或默认 metadata。
     * @Param: metadataSyncJobServiceProvider metadata 同步服务提供器。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public ReportVectorDocumentBuilder(ObjectProvider<ReportVectorMetadataSyncJobService> metadataSyncJobServiceProvider) {
        this.metadataSyncJobServiceProvider = metadataSyncJobServiceProvider;
    }

    /**
     * @Description: 批量构建向量文档。
     * @Logic: 遍历待向量化 chunk；metadata 服务存在时委托增强构建，否则使用默认 metadata。
     * @Param: report 研报主档；chunks 待向量化子切片。
     * @Return: 向量文档列表。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public List<Document> buildVectorDocuments(ReportDocument report, List<ReportChunk> chunks) {
        ReportVectorMetadataSyncJobService metadataService = metadataSyncJobServiceProvider == null ? null : metadataSyncJobServiceProvider.getIfAvailable();
        List<Document> vectorDocuments = new ArrayList<>(chunks.size());
        for (ReportChunk chunk : chunks) {
            if (metadataService != null) {
                vectorDocuments.add(metadataService.buildVectorDocument(report, chunk));
                continue;
            }
            vectorDocuments.add(buildDefaultDocument(report, chunk));
        }
        return vectorDocuments;
    }

    /**
     * @Description: 构建默认向量文档。
     * @Logic: 使用 report/chunk 基础字段填充 metadata，并保留结构化标签字段的空值占位以兼容检索侧字段读取。
     * @Param: report 研报主档；chunk 子切片。
     * @Return: Spring AI Document。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private Document buildDefaultDocument(ReportDocument report, ReportChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reportId", report.getId());
        metadata.put("chunkId", chunk.getId());
        metadata.put("chunkUid", chunk.getChunkUid());
        metadata.put("parentChunkUid", chunk.getParentChunkUid() == null ? "" : chunk.getParentChunkUid());
        metadata.put("chunkType", chunk.getChunkType());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("sectionPath", chunk.getSectionPath() == null ? "" : chunk.getSectionPath());
        metadata.put("tokenCount", chunk.getTokenCount() == null ? 0 : chunk.getTokenCount());
        metadata.put("startParagraphId", chunk.getStartParagraphId() == null ? 0 : chunk.getStartParagraphId());
        metadata.put("endParagraphId", chunk.getEndParagraphId() == null ? 0 : chunk.getEndParagraphId());
        metadata.put("startPageNumber", chunk.getStartPageNumber() == null ? 0 : chunk.getStartPageNumber());
        metadata.put("endPageNumber", chunk.getEndPageNumber() == null ? 0 : chunk.getEndPageNumber());
        metadata.put("title", report.getTitle());
        metadata.put("source", report.getSource());
        metadata.put("institution", report.getInstitution() == null ? "" : report.getInstitution());
        metadata.put("publishDate", report.getPublishDate() == null ? "" : report.getPublishDate().toString());
        metadata.put("reportThemeCode", "");
        metadata.put("reportThemeCodes", List.of());
        metadata.put("themeCode", "");
        metadata.put("industryCode", "");
        metadata.put("companyName", "");
        metadata.put("ticker", "");
        metadata.put("themeCodes", List.of());
        metadata.put("industryCodes", List.of());
        metadata.put("companyNames", List.of());
        metadata.put("tickers", List.of());
        return new Document(chunk.getChunkUid(), chunk.getChunkText(), metadata);
    }
}
