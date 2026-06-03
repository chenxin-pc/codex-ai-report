package com.example.aimilvusweb.service.tag;

import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportChunkTagJob;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportChunkTagJobMapper;
import com.example.aimilvusweb.service.ingest.ReportVectorMetadataSyncJobService;
import com.example.aimilvusweb.service.taxonomy.ResearchTaxonomySnapshotService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @Description: chunk 标签抽取 job 服务，负责任务创建、历史重打标入口、调度执行和状态流转。
 * @Logic: chunk 入库后创建 PENDING job；调度执行标签抽取，成功后触发 Milvus metadata sync job。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 创建或处理的 job 数量。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Service
public class ReportChunkTagJobService {

    /** job 待执行状态。 */
    private static final String STATUS_PENDING = "PENDING";
    /** 默认最大重试次数。 */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 默认调度批量大小。 */
    private static final int DEFAULT_BATCH_SIZE = 20;
    /** 历史重打标默认单批 chunk 数。 */
    private static final int DEFAULT_BACKFILL_LIMIT = 500;

    /** 标签 job Mapper。 */
    private final ReportChunkTagJobMapper tagJobMapper;
    /** chunk Mapper，用于读取待处理切片和历史重打标候选。 */
    private final ReportChunkMapper reportChunkMapper;
    /** 词库快照服务，用于获取当前 ACTIVE 词库版本。 */
    private final ResearchTaxonomySnapshotService taxonomySnapshotService;
    /** 标签抽取服务，用于真正生成并落库 report_chunk_tag。 */
    private final ReportChunkTagExtractionService tagExtractionService;
    /** 报告级标签服务，用于将 chunk 标签聚合为报告父标签。 */
    private final ReportDocumentTagService reportDocumentTagService;
    /** metadata sync job 服务，用于标签成功后触发 Milvus metadata 同步。 */
    private final ReportVectorMetadataSyncJobService metadataSyncJobService;

    /**
     * @Description: 初始化标签抽取 job 服务依赖。
     * @Logic: 保存 job、chunk、词库、标签抽取和 metadata 同步依赖，供创建与调度流程复用。
     * @Param: tagJobMapper 标签 job Mapper；reportChunkMapper chunk Mapper；taxonomySnapshotService 词库快照服务；tagExtractionService 标签抽取服务；metadataSyncJobService metadata 同步服务。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public ReportChunkTagJobService(ReportChunkTagJobMapper tagJobMapper,
                                    ReportChunkMapper reportChunkMapper,
                                    ResearchTaxonomySnapshotService taxonomySnapshotService,
                                    ReportChunkTagExtractionService tagExtractionService,
                                    ReportDocumentTagService reportDocumentTagService,
                                    ReportVectorMetadataSyncJobService metadataSyncJobService) {
        this.tagJobMapper = tagJobMapper;
        this.reportChunkMapper = reportChunkMapper;
        this.taxonomySnapshotService = taxonomySnapshotService;
        this.tagExtractionService = tagExtractionService;
        this.reportDocumentTagService = reportDocumentTagService;
        this.metadataSyncJobService = metadataSyncJobService;
    }

    /**
     * @Description: 为 chunk 创建标签抽取 job。
     * @Logic: 使用当前 ACTIVE 词库版本做幂等 key；已有同版本 job 时直接返回。
     * @Param: chunk 已落库子切片。
     * @Return: 新建或已存在的标签抽取 job。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    @Transactional
    public ReportChunkTagJob enqueue(ReportChunk chunk) {
        // 读取当前词库版本，确保 job 和标签结果可追溯。
        String dictionaryVersion = taxonomySnapshotService.currentSnapshot().dictionaryVersion();
        // 同一个 chunk/version 只允许一个抽取 job。
        ReportChunkTagJob existing = tagJobMapper.selectByChunkUidAndVersion(chunk.getChunkUid(), dictionaryVersion);
        if (existing != null) {
            return existing;
        }
        // 创建 PENDING job 等待调度器执行。
        Instant now = Instant.now();
        ReportChunkTagJob job = new ReportChunkTagJob();
        job.setJobUid(newJobUid());
        job.setReportId(chunk.getReportId());
        job.setChunkUid(chunk.getChunkUid());
        job.setDictionaryVersion(dictionaryVersion);
        job.setStatus(STATUS_PENDING);
        job.setAttemptCount(0);
        job.setMaxAttempts(DEFAULT_MAX_ATTEMPTS);
        job.setNextRunAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        tagJobMapper.insert(job);
        return job;
    }

    /**
     * @Description: 为指定报告的所有子切片创建标签抽取 job。
     * @Logic: 查询报告内 chunk 后逐条 enqueue，适合单报告补偿或重新打标入口。
     * @Param: reportId 研报 ID。
     * @Return: 创建或复用的 job 数量。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public int enqueueReport(Long reportId) {
        List<ReportChunk> chunks = reportChunkMapper.selectByReportId(reportId).stream()
                .filter(chunk -> "CHILD".equals(chunk.getChunkType()))
                .toList();
        for (ReportChunk chunk : chunks) {
            enqueue(chunk);
        }
        return chunks.size();
    }

    /**
     * @Description: 创建历史数据重打标 job。
     * @Logic: 拉取一批 CHILD chunk 并按当前词库版本创建 job，供管理员或定时补偿调用。
     * @Param: limit 单批 chunk 数上限。
     * @Return: 创建或复用的 job 数量。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public int enqueueBackfill(int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, DEFAULT_BACKFILL_LIMIT));
        List<ReportChunk> chunks = reportChunkMapper.selectAllChildren(normalizedLimit);
        for (ReportChunk chunk : chunks) {
            enqueue(chunk);
        }
        return chunks.size();
    }

    /**
     * @Description: 定时执行到期标签抽取 job。
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
     * @Description: 执行一批标签抽取 job。
     * @Logic: 查询到期任务后逐条处理，失败记录错误并延迟重试。
     * @Param: limit 批量上限。
     * @Return: 本次尝试处理的 job 数量。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public int runOnce(int limit) {
        // 按当前时间拉取到期可执行任务。
        List<ReportChunkTagJob> jobs = tagJobMapper.selectRunnableJobs(Instant.now(), Math.max(1, limit));
        // 逐条处理，避免坏数据阻塞整个队列。
        for (ReportChunkTagJob job : jobs) {
            processJob(job);
        }
        return jobs.size();
    }

    /**
     * @Description: 执行单个标签抽取 job。
     * @Logic: 标记处理中后读取 chunk，完成标签覆盖写入，并创建 metadata sync job。
     * @Param: job 待执行标签抽取任务。
     * @Return: 无（仅更新标签和 job 状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private void processJob(ReportChunkTagJob job) {
        Instant now = Instant.now();
        tagJobMapper.markProcessing(job.getId(), now);
        try {
            // chunk 不存在说明主数据异常，直接记录失败。
            ReportChunk chunk = requireChunk(job.getChunkUid());
            // 执行标签覆盖写入，结果以 MySQL report_chunk_tag 为主数据。
            tagExtractionService.extractAndPersist(chunk, job.getDictionaryVersion());
            // chunk 标签变化后重算报告级父标签，再同步整篇报告下的向量 metadata。
            reportDocumentTagService.refreshFromChunkTags(chunk.getReportId(), job.getDictionaryVersion());
            metadataSyncJobService.enqueueReport(chunk.getReportId());
            // 最后标记标签 job 成功。
            tagJobMapper.markSucceeded(job.getId(), Instant.now());
        } catch (RuntimeException e) {
            // 失败时保留错误摘要和重试时间，不影响其他 job。
            tagJobMapper.markFailed(job.getId(), abbreviate(e.getMessage()), Instant.now().plus(Duration.ofMinutes(1)), Instant.now());
        }
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
        String safeMessage = message == null || message.isBlank() ? "chunk tag extraction failed" : message;
        return safeMessage.length() > 512 ? safeMessage.substring(0, 512) : safeMessage;
    }
}
