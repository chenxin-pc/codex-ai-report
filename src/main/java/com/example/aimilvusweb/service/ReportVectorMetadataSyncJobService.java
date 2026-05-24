package com.example.aimilvusweb.service;

import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportDocument;
import com.example.aimilvusweb.entity.ReportVectorMetadataSyncJob;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import com.example.aimilvusweb.repository.ReportVectorMetadataSyncJobMapper;
import com.example.aimilvusweb.service.ReportTagMetadataService.TagMetadata;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @Description: Milvus metadata 同步 job 服务，负责创建、调度和执行向量 metadata 最终一致同步。
 * @Logic: 标签变化后按 tagSnapshotHash 幂等创建任务；执行时读取 MySQL 标签主数据并重新 upsert 向量文档 metadata。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 创建或执行的 job 数量。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Service
public class ReportVectorMetadataSyncJobService {

    /** metadata 版本，后续字段结构变化时可升级。 */
    private static final String METADATA_VERSION = "v1";
    /** job 待执行状态。 */
    private static final String STATUS_PENDING = "PENDING";
    /** 默认最大重试次数。 */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 默认调度批量大小。 */
    private static final int DEFAULT_BATCH_SIZE = 20;

    /** sync job Mapper。 */
    private final ReportVectorMetadataSyncJobMapper syncJobMapper;
    /** chunk Mapper，用于读取待同步文本和 sectionPath。 */
    private final ReportChunkMapper reportChunkMapper;
    /** report Mapper，用于补充研报标题、来源和日期 metadata。 */
    private final ReportDocumentMapper reportDocumentMapper;
    /** 标签 metadata 服务，用于读取标签摘要和计算 tagSnapshotHash。 */
    private final ReportTagMetadataService reportTagMetadataService;
    /** VectorStore 提供器，允许测试或本地未启用 Milvus 时降级。 */
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    /**
     * @Description: 初始化 metadata 同步服务依赖。
     * @Logic: 保存 job、chunk、report、标签和向量库依赖，供创建任务和调度执行复用。
     * @Param: syncJobMapper sync job Mapper；reportChunkMapper chunk Mapper；reportDocumentMapper report Mapper；reportTagMetadataService 标签 metadata 服务；vectorStoreProvider 向量库提供器。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public ReportVectorMetadataSyncJobService(ReportVectorMetadataSyncJobMapper syncJobMapper,
                                              ReportChunkMapper reportChunkMapper,
                                              ReportDocumentMapper reportDocumentMapper,
                                              ReportTagMetadataService reportTagMetadataService,
                                              ObjectProvider<VectorStore> vectorStoreProvider) {
        this.syncJobMapper = syncJobMapper;
        this.reportChunkMapper = reportChunkMapper;
        this.reportDocumentMapper = reportDocumentMapper;
        this.reportTagMetadataService = reportTagMetadataService;
        this.vectorStoreProvider = vectorStoreProvider;
    }

    /**
     * @Description: 为 chunk 创建 metadata 同步 job。
     * @Logic: 读取最新标签主数据计算快照哈希；相同 chunk/hash 已有 job 时不重复创建。
     * @Param: reportId 研报 ID；chunkUid 切片唯一标识。
     * @Return: 新建或已存在的 sync job。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    @Transactional
    public ReportVectorMetadataSyncJob enqueue(Long reportId, String chunkUid) {
        // 读取当前标签摘要作为同步任务的幂等依据。
        TagMetadata metadata = reportTagMetadataService.metadataForChunk(chunkUid);
        String tagSnapshotHash = reportTagMetadataService.snapshotHash(metadata);
        // 如果同一快照已经排队或完成，则复用已有任务。
        ReportVectorMetadataSyncJob existing = syncJobMapper.selectByChunkUidAndHash(chunkUid, tagSnapshotHash);
        if (existing != null) {
            return existing;
        }
        // 创建新的 PENDING 同步任务。
        Instant now = Instant.now();
        ReportVectorMetadataSyncJob job = new ReportVectorMetadataSyncJob();
        job.setJobUid(newJobUid());
        job.setReportId(reportId);
        job.setChunkUid(chunkUid);
        job.setMetadataVersion(METADATA_VERSION);
        job.setTagSnapshotHash(tagSnapshotHash);
        job.setStatus(STATUS_PENDING);
        job.setAttemptCount(0);
        job.setMaxAttempts(DEFAULT_MAX_ATTEMPTS);
        job.setNextRunAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        syncJobMapper.insert(job);
        return job;
    }

    /**
     * @Description: 定时执行到期的 metadata 同步 job。
     * @Logic: 按批量拉取 runnable job，并逐条执行，单条失败不会影响后续任务。
     * @Param: 无。
     * @Return: 无（仅调度副作用）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    @Scheduled(fixedDelayString = "${app.report-ingest-async.fixed-delay-ms:3000}")
    public void runScheduler() {
        runOnce(DEFAULT_BATCH_SIZE);
    }

    /**
     * @Description: 执行一批 metadata 同步 job。
     * @Logic: 查询到期任务后逐条处理，失败记录错误并延迟重试。
     * @Param: limit 批量上限。
     * @Return: 本次尝试处理的 job 数量。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public int runOnce(int limit) {
        // 按当前时间拉取到期可执行任务。
        List<ReportVectorMetadataSyncJob> jobs = syncJobMapper.selectRunnableJobs(Instant.now(), Math.max(1, limit));
        // 逐条执行，避免一个坏任务阻塞整个队列。
        for (ReportVectorMetadataSyncJob job : jobs) {
            processJob(job);
        }
        return jobs.size();
    }

    /**
     * @Description: 执行单个 metadata 同步 job。
     * @Logic: 标记处理中后读取 chunk/report/tag，并向 VectorStore upsert 带最新 metadata 的文档。
     * @Param: job 待执行同步任务。
     * @Return: 无（仅更新 Milvus 和 job 状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private void processJob(ReportVectorMetadataSyncJob job) {
        Instant now = Instant.now();
        syncJobMapper.markProcessing(job.getId(), now);
        try {
            // 向量库缺失时认为同步不可执行，交由失败重试与运维诊断处理。
            VectorStore vectorStore = requireVectorStore();
            // chunk 不存在时说明主数据异常，直接抛出明确错误。
            ReportChunk chunk = requireChunk(job.getChunkUid());
            // report 不存在时同样视为主数据异常。
            ReportDocument report = requireReport(chunk.getReportId());
            // 使用当前标签主数据构建最新 metadata 文档。
            vectorStore.add(List.of(buildVectorDocument(report, chunk)));
            // 写入成功后标记 job 成功。
            syncJobMapper.markSucceeded(job.getId(), Instant.now());
        } catch (RuntimeException e) {
            // 单 job 失败只记录错误和下次重试时间，不回滚 MySQL 标签主数据。
            syncJobMapper.markFailed(job.getId(), abbreviate(e.getMessage()), Instant.now().plus(Duration.ofMinutes(1)), Instant.now());
        }
    }

    /**
     * @Description: 构建携带标签 metadata 的向量文档。
     * @Logic: 基础字段来自 report/chunk，标签字段来自 MySQL 标签主数据，Document id 固定为 chunkUid 便于 upsert。
     * @Param: report 研报主档；chunk 子切片。
     * @Return: Spring AI Document。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public Document buildVectorDocument(ReportDocument report, ReportChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        TagMetadata tagMetadata = reportTagMetadataService.metadataForChunk(chunk.getChunkUid());
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
        metadata.put("themeCode", tagMetadata.primaryThemeCode());
        metadata.put("industryCode", tagMetadata.primaryIndustryCode());
        metadata.put("companyName", tagMetadata.primaryCompanyName());
        metadata.put("ticker", tagMetadata.primaryTicker());
        metadata.put("themeCodes", tagMetadata.themeCodes());
        metadata.put("industryCodes", tagMetadata.industryCodes());
        metadata.put("companyNames", tagMetadata.companyNames());
        metadata.put("tickers", tagMetadata.tickers());
        metadata.put("tagSnapshotHash", reportTagMetadataService.snapshotHash(tagMetadata));
        return new Document(chunk.getChunkUid(), chunk.getChunkText(), metadata);
    }

    /**
     * @Description: 获取可用 VectorStore。
     * @Logic: 未配置向量库时抛出明确异常，job 会记录失败并等待后续重试。
     * @Param: 无。
     * @Return: VectorStore 实例。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private VectorStore requireVectorStore() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new IllegalStateException("VectorStore is not configured for metadata sync");
        }
        return vectorStore;
    }

    /**
     * @Description: 读取并校验 chunk 主数据。
     * @Logic: 按 chunkUid 查询，未命中抛出异常供 job 记录失败原因。
     * @Param: chunkUid 切片唯一标识。
     * @Return: chunk 实体。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private ReportChunk requireChunk(String chunkUid) {
        ReportChunk chunk = reportChunkMapper.selectByChunkUid(chunkUid);
        if (chunk == null) {
            throw new IllegalArgumentException("Chunk not found: " + chunkUid);
        }
        return chunk;
    }

    /**
     * @Description: 读取并校验 report 主数据。
     * @Logic: 按 reportId 查询，未命中抛出异常供 job 记录失败原因。
     * @Param: reportId 研报 ID。
     * @Return: report 实体。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private ReportDocument requireReport(Long reportId) {
        ReportDocument report = reportDocumentMapper.selectById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("Report not found: " + reportId);
        }
        return report;
    }

    /**
     * @Description: 生成 job 唯一标识。
     * @Logic: 使用去横线 UUID，保持与现有 ingest_job 风格一致。
     * @Param: 无。
     * @Return: job 唯一标识。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private String newJobUid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * @Description: 截断错误消息。
     * @Logic: 数据库字段限制 512 字符，空错误兜底为固定文本。
     * @Param: message 原始错误消息。
     * @Return: 可安全入库的错误摘要。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private String abbreviate(String message) {
        String safeMessage = message == null || message.isBlank() ? "metadata sync failed" : message;
        return safeMessage.length() > 512 ? safeMessage.substring(0, 512) : safeMessage;
    }
}
