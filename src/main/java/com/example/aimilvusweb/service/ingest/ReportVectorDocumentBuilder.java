package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportDocument;
import com.example.aimilvusweb.service.ingest.ReportAuthorService;
import com.example.aimilvusweb.service.ingest.ReportAuthorService.AuthorMetadata;
import com.example.aimilvusweb.service.ingest.ReportVectorMetadataSyncJobService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 入库向量文档构建器，负责将 CHILD chunk 转换为 Spring AI Document。
 * @Logic: 结构化 metadata 服务可用时优先使用增强 metadata；不可用时回退默认 report/chunk metadata，并统一补充作者与标签字段。
 * @Param: 无。
 * @Return: 无（由 Spring 管理并供向量写入链路调用）。
 * @author: cx
 * @Date: 2026-05-31 20:50:00
 */
@Component
public class ReportVectorDocumentBuilder {

    /** metadata 同步服务提供器，用于可选增强 Milvus Document metadata。 */
    private final ObjectProvider<ReportVectorMetadataSyncJobService> metadataSyncJobServiceProvider;
    /** 作者服务提供器，用于从 MySQL 作者事实表投影 author metadata。 */
    private final ObjectProvider<ReportAuthorService> reportAuthorServiceProvider;

    /**
     * @Description: 初始化向量文档构建器。
     * @Logic: 保存 metadata 与作者服务提供器，构建时按可用性选择增强 metadata，并从作者服务补充作者字段。
     * @Param: metadataSyncJobServiceProvider metadata 同步服务提供器；reportAuthorServiceProvider 作者服务提供器。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-31 20:50:00
     */
    @Autowired
    public ReportVectorDocumentBuilder(ObjectProvider<ReportVectorMetadataSyncJobService> metadataSyncJobServiceProvider,
                                       ObjectProvider<ReportAuthorService> reportAuthorServiceProvider) {
        this.metadataSyncJobServiceProvider = metadataSyncJobServiceProvider;
        this.reportAuthorServiceProvider = reportAuthorServiceProvider;
    }

    /**
     * @Description: 兼容旧构造器初始化向量文档构建器。
     * @Logic: 旧测试或局部构造未提供作者服务时，构建出的 metadata 使用空作者集合。
     * @Param: metadataSyncJobServiceProvider metadata 同步服务提供器。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public ReportVectorDocumentBuilder(ObjectProvider<ReportVectorMetadataSyncJobService> metadataSyncJobServiceProvider) {
        this(metadataSyncJobServiceProvider, null);
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
                vectorDocuments.add(withAuthorMetadata(report, metadataService.buildVectorDocument(report, chunk)));
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
        putAuthorMetadata(metadata, authorMetadata(report));
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

    /**
     * @Description: 为已有向量文档补充作者 metadata。
     * @Logic: metadata 同步服务构建标签字段后，再以 MySQL 作者事实表覆盖 author 字段。
     * @Param: report 研报主档；document 已构建向量文档。
     * @Return: 包含作者 metadata 的新 Document。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private Document withAuthorMetadata(ReportDocument report, Document document) {
        // 复制原 metadata，避免修改 Document 内部集合造成副作用。
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        // 从 MySQL 作者事实表投影 author metadata。
        putAuthorMetadata(metadata, authorMetadata(report));
        // 使用原 Document id 和文本重建文档，保持向量 upsert 身份不变。
        return new Document(document.getId(), document.getText(), metadata);
    }

    /**
     * @Description: 写入作者 metadata 字段。
     * @Logic: 同时提供 primary author、展示作者列表、规范化作者列表和 Milvus 可过滤 authorText。
     * @Param: metadata 待写入 metadata；authorMetadata 作者投影对象。
     * @Return: 无（仅修改 metadata）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private void putAuthorMetadata(Map<String, Object> metadata, AuthorMetadata authorMetadata) {
        // 写入首个展示作者，便于展示和单值兜底。
        metadata.put("author", authorMetadata.primaryAuthor());
        // 写入展示作者列表，便于诊断和后续扩展。
        metadata.put("authors", authorMetadata.authors());
        // 写入首个规范化作者，便于精确等值过滤。
        metadata.put("normalizedAuthor", authorMetadata.primaryNormalizedAuthor());
        // 写入所有规范化作者，便于多值 OR 过滤构造。
        metadata.put("normalizedAuthors", authorMetadata.normalizedAuthors());
        // 写入多作者文本投影，便于 Milvus like 过滤完整作者名。
        metadata.put("authorText", authorMetadata.authorText());
    }

    /**
     * @Description: 读取研报作者 metadata。
     * @Logic: 作者服务未注入时返回空作者集合，生产链路会通过构造器注入作者服务。
     * @Param: report 研报主档。
     * @Return: 作者 metadata。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private AuthorMetadata authorMetadata(ReportDocument report) {
        // 获取作者服务，旧测试构造时可能为空。
        ReportAuthorService reportAuthorService = reportAuthorServiceProvider == null ? null : reportAuthorServiceProvider.getIfAvailable();
        // 服务缺失时返回空作者 metadata，避免伪造作者。
        if (reportAuthorService == null) {
            return new AuthorMetadata("", List.of(), "", List.of(), "");
        }
        // 从 MySQL 作者事实表读取作者 metadata。
        return reportAuthorService.metadataForReport(report.getId());
    }
}
