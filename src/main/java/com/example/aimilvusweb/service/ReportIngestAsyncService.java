package com.example.aimilvusweb.service;

import com.example.aimilvusweb.config.ReportIngestAsyncProperties;
import com.example.aimilvusweb.dto.IngestJobStatusRespDTO;
import com.example.aimilvusweb.dto.IngestMetricsRespDTO;
import com.example.aimilvusweb.dto.ReportObservationRespDTO;
import com.example.aimilvusweb.dto.ReportIngestStageEventRespDTO;
import com.example.aimilvusweb.dto.ReportUploadRespDTO;
import com.example.aimilvusweb.entity.IngestJob;
import com.example.aimilvusweb.entity.ReportIngestStageEvent;
import com.example.aimilvusweb.enums.IngestStageStatusEnum;
import com.example.aimilvusweb.repository.IngestJobMapper;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import com.example.aimilvusweb.repository.ReportIngestStageEventMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * @Description: ReportIngestAsyncService类，负责异步导入任务提交、轮询执行与链路查询。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:15:00
 */
@Service
public class ReportIngestAsyncService {

    /** 任务待执行状态。 */
    private static final String STATUS_PENDING = "PENDING";
    /** 任务执行中状态。 */
    private static final String STATUS_PROCESSING = "PROCESSING";
    /** 任务成功状态。 */
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    /** 可重试失败状态。 */
    private static final String STATUS_FAILED_RETRYABLE = "FAILED_RETRYABLE";
    /** 最终失败状态。 */
    private static final String STATUS_FAILED_FINAL = "FAILED_FINAL";
    /** OCR 阶段标识。 */
    private static final String STAGE_OCR = "OCR";
    /** Chunk 阶段标识。 */
    private static final String STAGE_CHUNK = "CHUNK";
    /** Vector 阶段标识。 */
    private static final String STAGE_VECTOR = "VECTOR";

    /** 异步任务仓储。 */
    private final IngestJobMapper ingestJobMapper;
    /** 阶段事件仓储。 */
    private final ReportIngestStageEventMapper stageEventMapper;
    /** 研报主档仓储。 */
    private final ReportDocumentMapper reportDocumentMapper;
    /** 导入执行服务。 */
    private final ReportIngestService reportIngestService;
    /** 报告级标签服务，用于写入导入表单显式标签。 */
    private final ReportDocumentTagService reportDocumentTagService;
    /** 结构化词库快照服务，用于记录导入标签使用的词库版本。 */
    private final ResearchTaxonomySnapshotService taxonomySnapshotService;
    /** 异步调度配置。 */
    private final ReportIngestAsyncProperties properties;
    /** OCR 模型名。 */
    private final String ocrModelName;
    /** 切片模型名。 */
    private final String chunkModelName;
    /** 向量模型名。 */
    private final String vectorModelName;

    /**
     * @Description: 初始化异步导入服务依赖与模型配置。
     * @Logic: 注入任务仓储、阶段事件仓储、导入执行服务和异步参数；缓存 OCR/切片/向量模型名用于观测记录。
     * @Param: ingestJobMapper 任务仓储；stageEventMapper 阶段事件仓储；reportIngestService 导入执行服务；properties 异步配置；ocrModelName/chunkModelName/vectorModelName 模型标识。
     * @Return: 无（仅初始化对象状态）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    public ReportIngestAsyncService(IngestJobMapper ingestJobMapper,
                                    ReportIngestStageEventMapper stageEventMapper,
                                    ReportDocumentMapper reportDocumentMapper,
                                    ReportIngestService reportIngestService,
                                    ReportDocumentTagService reportDocumentTagService,
                                    ResearchTaxonomySnapshotService taxonomySnapshotService,
                                    ReportIngestAsyncProperties properties,
                                    @Value("${app.ocr.model:qwen-vl-ocr-latest}") String ocrModelName,
                                    @Value("${spring.ai.openai.chat.options.model:qwen-plus-latest}") String chunkModelName,
                                    @Value("${spring.ai.openai.embedding.options.model:text-embedding-v3}") String vectorModelName) {
        this.ingestJobMapper = ingestJobMapper;
        this.stageEventMapper = stageEventMapper;
        this.reportDocumentMapper = reportDocumentMapper;
        this.reportIngestService = reportIngestService;
        this.reportDocumentTagService = reportDocumentTagService;
        this.taxonomySnapshotService = taxonomySnapshotService;
        this.properties = properties;
        this.ocrModelName = ocrModelName;
        this.chunkModelName = chunkModelName;
        this.vectorModelName = vectorModelName;
    }

    /**
     * @Description: 提交异步导入任务并返回 jobId，上传阶段仅做文件落盘与任务入库。
     * @Logic: 校验文件后写入 spool 目录，初始化三阶段状态为 PENDING，返回带 jobId 的响应。
     * @Param: file 上传文件；title/source/institution/publishDate 报告元信息。
     * @Return: 上传响应对象，包含 jobId 与提交成功消息。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    @Transactional
    public ReportUploadRespDTO submit(MultipartFile file,
                                      String title,
                                      String source,
                                      String institution,
                                      LocalDate publishDate,
                                      String themeTags,
                                      String industryTags,
                                      String companyTags,
                                      String tickerTags) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Report file is required");
        }
        String jobUid = UUID.randomUUID().toString().replace("-", "");
        String reportTitle = (title == null || title.isBlank()) ? file.getOriginalFilename() : title.trim();
        Instant now = Instant.now();

        Path spoolFile = persistUploadFile(jobUid, file);

        IngestJob job = new IngestJob();
        job.setJobUid(jobUid);
        job.setReportTitleSnapshot(reportTitle);
        job.setTitleSearchKey(normalizeTitle(reportTitle));
        job.setSource(source == null || source.isBlank() ? "uploaded" : source.trim());
        job.setInstitution(institution == null ? null : institution.trim());
        job.setPublishDate(publishDate);
        job.setThemeTags(normalizeOptionalTags(themeTags));
        job.setIndustryTags(normalizeOptionalTags(industryTags));
        job.setCompanyTags(normalizeOptionalTags(companyTags));
        job.setTickerTags(normalizeOptionalTags(tickerTags));
        job.setOriginalFilename(file.getOriginalFilename());
        job.setFilePath(spoolFile.toString());
        job.setOcrStatus(STATUS_PENDING);
        job.setChunkStatus(STATUS_PENDING);
        job.setVectorStatus(STATUS_PENDING);
        job.setOcrAttemptCount(0);
        job.setChunkAttemptCount(0);
        job.setVectorAttemptCount(0);
        job.setNextRunAt(now);
        job.setPriority(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        ingestJobMapper.insert(job);
        return new ReportUploadRespDTO(jobUid, null, reportTitle, 0, "Upload accepted, ingest scheduled");
    }

    /**
     * @Description: 查询单个导入任务状态，返回三阶段状态、重试次数与错误摘要。
     * @Logic: 按 jobUid 查表并组装响应对象，未命中抛出参数异常。
     * @Param: jobUid 任务唯一标识。
     * @Return: 任务状态响应对象。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    public IngestJobStatusRespDTO getJobStatus(String jobUid) {
        IngestJob job = requireJob(jobUid);
        return new IngestJobStatusRespDTO(
                job.getJobUid(),
                job.getReportId(),
                job.getReportTitleSnapshot(),
                job.getOcrStatus(),
                job.getChunkStatus(),
                job.getVectorStatus(),
                safeInt(job.getOcrAttemptCount()),
                safeInt(job.getChunkAttemptCount()),
                safeInt(job.getVectorAttemptCount()),
                job.getNextRunAt(),
                job.getLastErrorCode(),
                job.getLastErrorMessage());
    }

    /**
     * @Description: 按 reportId 查询导入阶段时间线，用于展示单报告全链路观测。
     * @Logic: 按 reportId 拉取阶段事件并按时间序列映射为响应列表。
     * @Param: reportId 报告ID。
     * @Return: 阶段事件列表。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    public List<ReportIngestStageEventRespDTO> getTimelineByReportId(Long reportId) {
        return stageEventMapper.selectTimelineByReportId(reportId).stream().map(this::toStageResp).toList();
    }

    /**
     * @Description: 按标题关键词检索阶段事件，用于快速定位报告处理链路。
     * @Logic: 将关键词标准化后执行模糊检索，限制返回规模避免大结果集。
     * @Param: titleKeyword 标题关键词；limit 返回条数上限。
     * @Return: 阶段事件列表。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    public List<ReportIngestStageEventRespDTO> searchTimelineByTitle(String titleKeyword, int limit) {
        return stageEventMapper.selectByTitleKeyword(normalizeTitle(titleKeyword), Math.max(1, Math.min(limit, 200))).stream()
                .map(this::toStageResp)
                .toList();
    }

    /**
     * @Description: 汇总当前导入积压与最终失败指标，供页面和运维快速判断链路健康度。
     * @Logic: 分阶段按状态计数，分别返回 pending 与 failed_final 两类核心指标。
     * @Param: 无。
     * @Return: 导入指标响应对象。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    public IngestMetricsRespDTO getMetrics() {
        return new IngestMetricsRespDTO(
                ingestJobMapper.countByStageStatus(STAGE_OCR, STATUS_PENDING),
                ingestJobMapper.countByStageStatus(STAGE_CHUNK, STATUS_PENDING),
                ingestJobMapper.countByStageStatus(STAGE_VECTOR, STATUS_PENDING),
                ingestJobMapper.countByStageStatus(STAGE_OCR, STATUS_FAILED_FINAL),
                ingestJobMapper.countByStageStatus(STAGE_CHUNK, STATUS_FAILED_FINAL),
                ingestJobMapper.countByStageStatus(STAGE_VECTOR, STATUS_FAILED_FINAL)
        );
    }

    /**
     * @Description: 查询研报观测列表，支持标题关键词过滤。
     * @Logic: 委托主档 Mapper 拉取列表，并拼接每篇研报最新异步任务状态供前端观测。
     * @Param: titleKeyword 标题关键词；limit 返回数量上限。
     * @Return: 研报观测列表。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:59:00
     */
    public List<ReportObservationRespDTO> listObservations(String titleKeyword, int limit) {
        String normalizedKeyword = titleKeyword == null ? "" : titleKeyword.trim();
        int normalizedLimit = Math.max(1, Math.min(limit, 200));
        return reportDocumentMapper.selectObservations(normalizedKeyword, normalizedLimit).stream()
                .map(item -> new ReportObservationRespDTO(
                        item.reportId(),
                        item.title(),
                        item.source(),
                        item.institution(),
                        item.publishDate(),
                        item.createdAt(),
                        item.jobUid(),
                        statusLabel(item.ocrStatus()),
                        statusLabel(item.chunkStatus()),
                        statusLabel(item.vectorStatus()),
                        item.lastErrorCode(),
                        item.updatedAt()))
                .toList();
    }

    /**
     * @Description: OCR 阶段定时调度入口。
     * @Logic: 固定延迟触发 OCR 阶段批量拉取与执行。
     * @Param: 无。
     * @Return: 无（仅调度副作用）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    @Scheduled(fixedDelayString = "${app.report-ingest-async.fixed-delay-ms:3000}")
    public void runOcrScheduler() {
        runStage(STAGE_OCR);
    }

    /**
     * @Description: Chunk 阶段定时调度入口。
     * @Logic: 固定延迟触发 Chunk 阶段批量拉取与执行。
     * @Param: 无。
     * @Return: 无（仅调度副作用）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    @Scheduled(fixedDelayString = "${app.report-ingest-async.fixed-delay-ms:3000}")
    public void runChunkScheduler() {
        // 调度入口只负责触发，不直接执行业务，便于统一复用 runStage 的批处理与容错逻辑。
        runStage(STAGE_CHUNK);
    }

    /**
     * @Description: Vector 阶段定时调度入口。
     * @Logic: 固定延迟触发 Vector 阶段批量拉取与执行。
     * @Param: 无。
     * @Return: 无（仅调度副作用）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    @Scheduled(fixedDelayString = "${app.report-ingest-async.fixed-delay-ms:3000}")
    public void runVectorScheduler() {
        runStage(STAGE_VECTOR);
    }

    /**
     * @Description: 按阶段批量拉取可执行任务并逐条处理。
     * @Logic: 依据阶段和 next_run_at 过滤待执行任务，按批量上限遍历调用单任务处理。
     * @Param: stage 阶段标识（OCR/CHUNK/VECTOR）。
     * @Return: 无（仅更新任务状态与事件）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private void runStage(String stage) {
        // 只拉取“当前阶段可执行 + 到达 next_run_at + 状态为 PENDING”的任务，避免跨阶段串扰。
        List<IngestJob> jobs = ingestJobMapper.selectRunnableForStage(stage, STATUS_PENDING, Instant.now(), properties.getBatchSize());
        // 按批次串行推进阶段任务，避免单轮调度把数据库与模型服务打满。
        for (IngestJob job : jobs) {
            // 单任务执行拆到 processSingle，确保每个任务都有独立事务和阶段事件。
            processSingle(stage, job.getJobUid());
        }
    }

    /**
     * @Description: 执行单个任务的单阶段处理。
     * @Logic: 先更新尝试次数与处理中状态，再执行阶段逻辑；成功写成功事件，失败按可重试策略写回状态与退避时间。
     * @Param: stage 阶段标识；jobUid 任务唯一标识。
     * @Return: 无（仅更新状态和事件）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    @Transactional
    void processSingle(String stage, String jobUid) {
        // 先按 jobUid 读取最新任务快照，避免使用外层过期对象导致状态覆盖。
        IngestJob job = requireJob(jobUid);
        // 统一记录阶段起始时间，后续事件耗时从这里计算。
        Instant startedAt = Instant.now();
        // 每进入一次阶段处理都累加 attempt，用于重试上限和问题排查。
        int attempt = increaseAttempt(job, stage);
        // 进入执行前先标记为 PROCESSING，避免同阶段并发重复拾取同一任务。
        markStatus(job, stage, STATUS_PROCESSING);
        // 先落库状态，保证即使进程异常也能看见“处理中”痕迹。
        ingestJobMapper.updateStatusAndAttempt(job);
        try {
            // 按阶段分派执行：CHUNK 分支会读取 paragraph atoms 产出 child chunks。
            int outputSize = switch (stage) {
                case STAGE_OCR -> runOcrStage(job);
                case STAGE_CHUNK -> runChunkStage(job);
                default -> runVectorStage(job);
            };
            // 阶段成功后回写 SUCCEEDED，推进下一阶段调度条件。
            markStatus(job, stage, STATUS_SUCCEEDED);
            // 成功后清空错误字段，避免历史错误误导观测页面。
            job.setLastErrorCode(null);
            job.setLastErrorMessage(null);
            // next_run_at 置为 now，允许后续阶段在下一轮调度立即可见。
            job.setNextRunAt(Instant.now());
            // 更新时间用于任务列表排序和耗时粗略估算。
            job.setUpdatedAt(Instant.now());
            // 持久化成功态与计数器。
            ingestJobMapper.updateStatusAndAttempt(job);
            // 写阶段事件：包含耗时、模型名、输出规模，供“处理链路”页展示。
            saveStageEvent(job, stage, attempt, STATUS_SUCCEEDED, modelNameOf(stage), null, null, startedAt, outputSize);
        } catch (Exception ex) {
            // 根据异常类型判断是否允许退避重试。
            boolean retryable = isRetryable(ex);
            // 事件表里记录“本次尝试最终态”：可重试未达上限记为 PENDING，否则 FAILED_FINAL。
            String failureStatus = retryable && attempt < properties.getMaxAttempts() ? STATUS_PENDING : STATUS_FAILED_FINAL;
            // 可重试异常进入退避重试，不可重试或超过阈值则终态失败，避免无限循环。
            markStatus(job, stage, retryable ? STATUS_FAILED_RETRYABLE : STATUS_FAILED_FINAL);
            if (retryable && attempt < properties.getMaxAttempts()) {
                // 回退为 PENDING，交给下一轮定时调度重试。
                markStatus(job, stage, STATUS_PENDING);
            }
            // 标准化错误码和短错误文案，便于前端和 SQL 检索。
            job.setLastErrorCode(errorCode(ex));
            job.setLastErrorMessage(shortMessage(ex));
            // 可重试任务按 attempt 退避；不可重试任务立即进入最终可见状态。
            job.setNextRunAt(retryable && attempt < properties.getMaxAttempts() ? Instant.now().plusMillis(backoffMs(attempt)) : Instant.now());
            // 更新时间，体现最新失败时刻。
            job.setUpdatedAt(Instant.now());
            // 持久化失败态与下一次调度时间。
            ingestJobMapper.updateStatusAndAttempt(job);
            // 写失败事件，串联 traceId + 错误信息，支持链路排障。
            saveStageEvent(job, stage, attempt, failureStatus, modelNameOf(stage), job.getLastErrorCode(), job.getLastErrorMessage(), startedAt, null);
        }
    }

    /**
     * @Description: 执行 OCR 阶段。
     * @Logic: 将落盘文件包装为 MultipartFile，复用既有 OCR 与主档入库逻辑，并回写 reportId 绑定。
     * @Param: job 导入任务对象。
     * @Return: 阶段输出条目数。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private int runOcrStage(IngestJob job) throws IOException {
        MultipartFile file = new StoredPdfMultipartFile(job.getOriginalFilename(), Path.of(job.getFilePath()));
        Long reportId = reportIngestService.ingestOcrStage(file, job.getReportTitleSnapshot(), job.getSource(), job.getInstitution(), job.getPublishDate(), job.getReportId());
        ingestJobMapper.bindReportId(job.getJobUid(), reportId, Instant.now());
        reportDocumentTagService.refreshFromImportMetadata(reportId,
                taxonomySnapshotService.currentSnapshot().dictionaryVersion(),
                job.getThemeTags(),
                job.getIndustryTags(),
                job.getCompanyTags(),
                job.getTickerTags());
        job.setReportId(reportId);
        return 1;
    }

    /**
     * @Description: 标准化可选标签输入。
     * @Logic: 空白输入统一保存为 null，非空输入保留原始分隔结构供标签服务解析。
     * @Param: value 原始标签输入。
     * @Return: 标准化后的标签文本。
     */
    private String normalizeOptionalTags(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * @Description: 执行 Chunk 阶段。
     * @Logic: 校验 reportId 后调用切片阶段逻辑，返回生成子切片数量。
     * @Param: job 导入任务对象。
     * @Return: 阶段输出条目数。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private int runChunkStage(IngestJob job) {
        // CHUNK 阶段强依赖 reportId（OCR 阶段创建主档后回填），缺失时直接失败防止脏数据。
        if (job.getReportId() == null) {
            throw new IllegalStateException("Missing reportId for chunk stage");
        }
        // 进入核心切片服务：读取 paragraph atoms -> 语义切分 -> chunk/diagnostic 落库。
        return reportIngestService.ingestChunkStage(job.getReportId());
    }

    /**
     * @Description: 执行 Vector 阶段。
     * @Logic: 校验 reportId 后调用向量化阶段逻辑，返回入向量条目数量。
     * @Param: job 导入任务对象。
     * @Return: 阶段输出条目数。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private int runVectorStage(IngestJob job) {
        if (job.getReportId() == null) {
            throw new IllegalStateException("Missing reportId for vector stage");
        }
        return reportIngestService.ingestVectorStage(job.getReportId());
    }

    /**
     * @Description: 写入阶段事件记录。
     * @Logic: 组装阶段状态、耗时、模型、错误摘要与 trace 信息后落库，供页面和排障查询。
     * @Param: job 任务对象；stage 阶段；attempt 尝试次数；status 阶段状态；modelName 模型名；errorCode/errorMessage 错误信息；startedAt 开始时间；outputSize 输出规模。
     * @Return: 无（仅数据库副作用）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private void saveStageEvent(IngestJob job,
                                String stage,
                                int attempt,
                                String status,
                                String modelName,
                                String errorCode,
                                String errorMessage,
                                Instant startedAt,
                                Integer outputSize) {
        if (job.getReportId() == null) {
            IngestJob latest = ingestJobMapper.selectByJobUid(job.getJobUid());
            if (latest != null) {
                job.setReportId(latest.getReportId());
            }
        }
        Instant finishedAt = Instant.now();
        ReportIngestStageEvent event = new ReportIngestStageEvent();
        event.setJobUid(job.getJobUid());
        event.setReportId(job.getReportId());
        event.setReportTitleSnapshot(job.getReportTitleSnapshot());
        event.setTitleSearchKey(job.getTitleSearchKey());
        event.setStage(stage);
        event.setAttempt(attempt);
        event.setStatus(status);
        event.setModelName(modelName);
        event.setInputSize(1);
        event.setOutputSize(outputSize);
        event.setStartedAt(startedAt);
        event.setFinishedAt(finishedAt);
        event.setDurationMs(Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli()));
        event.setErrorCode(errorCode);
        event.setErrorMessageShort(errorMessage);
        event.setTraceId(job.getJobUid() + "-" + stage + "-" + attempt);
        event.setCreatedAt(finishedAt);
        stageEventMapper.insert(event);
    }

    /**
     * @Description: 将事件实体映射为接口响应对象。
     * @Logic: 提取统一字段并构造 DTO，避免控制器直接依赖实体。
     * @Param: event 阶段事件实体。
     * @Return: 阶段事件响应对象。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private ReportIngestStageEventRespDTO toStageResp(ReportIngestStageEvent event) {
        return new ReportIngestStageEventRespDTO(
                event.getJobUid(),
                event.getReportId(),
                event.getReportTitleSnapshot(),
                event.getStage(),
                safeInt(event.getAttempt()),
                statusLabel(event.getStatus()),
                event.getModelName(),
                event.getInputSize(),
                event.getOutputSize(),
                event.getStartedAt(),
                event.getFinishedAt(),
                event.getDurationMs(),
                event.getErrorCode(),
                event.getErrorMessageShort(),
                event.getTraceId(),
                event.getCreatedAt());
    }

    /**
     * @Description: 将内部状态码转换为中文展示文案。
     * @Logic: 通过统一状态枚举完成映射，未知状态保留原值以便排障。
     * @Param: statusCode 内部状态码。
     * @Return: 中文状态文案或原始状态码。
     * @author: cx
     * @Date: 2026-05-20 10:31:00
     */
    private String statusLabel(String statusCode) {
        return IngestStageStatusEnum.toLabel(statusCode);
    }

    /**
     * @Description: 按任务 ID 查询并校验任务存在性。
     * @Logic: 查库未命中时抛出参数异常，避免后续阶段空指针或脏更新。
     * @Param: jobUid 任务唯一标识。
     * @Return: 任务实体。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private IngestJob requireJob(String jobUid) {
        IngestJob job = ingestJobMapper.selectByJobUid(jobUid);
        if (job == null) {
            throw new IllegalArgumentException("Ingest job not found: " + jobUid);
        }
        return job;
    }

    /**
     * @Description: 将上传文件持久化到本地 spool 目录。
     * @Logic: 创建目录、清洗目标文件名、覆盖写入并返回绝对路径；写入失败抛出状态异常。
     * @Param: jobUid 任务唯一标识；file 上传文件。
     * @Return: 落盘文件路径。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private Path persistUploadFile(String jobUid, MultipartFile file) {
        String original = file.getOriginalFilename() == null ? "report.pdf" : file.getOriginalFilename();
        String targetName = jobUid + "-" + original.replaceAll("[^A-Za-z0-9._-]", "_");
        Path dir = Path.of(properties.getSpoolDir());
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(targetName);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist uploaded file", e);
        }
    }

    /**
     * @Description: 增加指定阶段的尝试计数。
     * @Logic: 根据阶段更新对应 attempt 字段并返回更新后的计数值。
     * @Param: job 任务对象；stage 阶段标识。
     * @Return: 当前阶段最新尝试次数。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private int increaseAttempt(IngestJob job, String stage) {
        if (STAGE_OCR.equals(stage)) {
            int value = safeInt(job.getOcrAttemptCount()) + 1;
            job.setOcrAttemptCount(value);
            return value;
        }
        if (STAGE_CHUNK.equals(stage)) {
            int value = safeInt(job.getChunkAttemptCount()) + 1;
            job.setChunkAttemptCount(value);
            return value;
        }
        int value = safeInt(job.getVectorAttemptCount()) + 1;
        job.setVectorAttemptCount(value);
        return value;
    }

    /**
     * @Description: 更新任务指定阶段状态。
     * @Logic: 根据阶段将状态写入 ocrStatus/chunkStatus/vectorStatus 对应字段。
     * @Param: job 任务对象；stage 阶段标识；status 目标状态。
     * @Return: 无（仅更新对象状态）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private void markStatus(IngestJob job, String stage, String status) {
        if (STAGE_OCR.equals(stage)) {
            job.setOcrStatus(status);
        } else if (STAGE_CHUNK.equals(stage)) {
            job.setChunkStatus(status);
        } else {
            job.setVectorStatus(status);
        }
    }

    /**
     * @Description: 计算重试退避时长。
     * @Logic: 第 1/2/3 次尝试分别映射 first/second/third 回退配置。
     * @Param: attempt 当前尝试次数。
     * @Return: 退避毫秒值。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private long backoffMs(int attempt) {
        return switch (attempt) {
            case 1 -> properties.getBackoffFirstMs();
            case 2 -> properties.getBackoffSecondMs();
            default -> properties.getBackoffThirdMs();
        };
    }

    /**
     * @Description: 判断异常是否可重试。
     * @Logic: 基于异常消息关键词识别网络超时、限流和临时服务不可用场景。
     * @Param: ex 异常对象。
     * @Return: true 表示可重试；false 表示不可重试。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private boolean isRetryable(Exception ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("timeout")
                || message.contains("timed out")
                || message.contains("429")
                || message.contains("too many requests")
                || message.contains("503")
                || message.contains("502")
                || message.contains("connection reset");
    }

    /**
     * @Description: 生成错误码。
     * @Logic: 优先使用异常类名，缺失时回退 UNKNOWN_ERROR。
     * @Param: ex 异常对象。
     * @Return: 错误码字符串。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private String errorCode(Exception ex) {
        String name = ex.getClass().getSimpleName();
        return name == null || name.isBlank() ? "UNKNOWN_ERROR" : name;
    }

    /**
     * @Description: 生成短错误消息。
     * @Logic: 读取异常消息并截断到 500 字符以内，缺失时回退错误码。
     * @Param: ex 异常对象。
     * @Return: 短错误消息。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private String shortMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return errorCode(ex);
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /**
     * @Description: 标准化标题检索键。
     * @Logic: 转小写并移除空白字符，用于标题模糊检索一致化。
     * @Param: value 原始标题。
     * @Return: 标准化标题键。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private String normalizeTitle(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * @Description: 返回阶段对应模型名。
     * @Logic: OCR/CHUNK/VECTOR 分别映射 OCR、Chat 和 Embedding 模型配置。
     * @Param: stage 阶段标识。
     * @Return: 模型名称。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private String modelNameOf(String stage) {
        if (STAGE_OCR.equals(stage)) {
            return ocrModelName;
        }
        if (STAGE_CHUNK.equals(stage)) {
            return chunkModelName;
        }
        return vectorModelName;
    }

    /**
     * @Description: 安全转换可空整型。
     * @Logic: 输入为 null 时返回 0，避免计数与响应字段空值传播。
     * @Param: value 可空整型。
     * @Return: 非空整型值。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
