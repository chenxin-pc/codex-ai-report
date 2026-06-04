package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.infra.text.TextEncodingRepairUtils;
import com.example.aimilvusweb.config.ReportIngestAsyncProperties;
import com.example.aimilvusweb.dto.IngestJobStatusRespDTO;
import com.example.aimilvusweb.dto.IngestMetricsRespDTO;
import com.example.aimilvusweb.dto.ReportIngestStageEventRespDTO;
import com.example.aimilvusweb.dto.ReportObservationRespDTO;
import com.example.aimilvusweb.dto.ReportUploadRespDTO;
import com.example.aimilvusweb.entity.IngestJob;
import com.example.aimilvusweb.entity.ReportIngestStageEvent;
import com.example.aimilvusweb.enums.IngestStageEnum;
import com.example.aimilvusweb.enums.IngestStageStatusEnum;
import com.example.aimilvusweb.repository.IngestJobMapper;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import com.example.aimilvusweb.repository.ReportIngestStageEventMapper;
import com.example.aimilvusweb.service.ingest.IngestStageDefinition;
import com.example.aimilvusweb.service.ingest.IngestStageExecutor;
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * @Description: 异步研报导入服务，负责任务提交、阶段调度触发、状态查询和导入观测查询。
 * @Logic: 上传阶段只落盘并创建任务；调度阶段委托 IngestStageExecutor 执行 OCR、CHUNK、VECTOR 的统一状态生命周期。
 * @Param: 无。
 * @Return: 无（由各方法返回任务、指标或观测响应）。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Service
public class ReportIngestAsyncService {

    /** 任务仓储，用于创建任务、查询任务和调度取数。 */
    private final IngestJobMapper ingestJobMapper;
    /** 阶段事件仓储，用于查询导入时间线。 */
    private final ReportIngestStageEventMapper stageEventMapper;
    /** 研报主档仓储，用于查询导入观测列表。 */
    private final ReportDocumentMapper reportDocumentMapper;
    /** 阶段执行模板，负责单任务单阶段状态推进。 */
    private final IngestStageExecutor ingestStageExecutor;
    /** 异步调度配置，提供批量大小、重试和 spool 目录。 */
    private final ReportIngestAsyncProperties properties;
    /** 阶段定义映射，绑定阶段与对应模型名。 */
    private final Map<IngestStageEnum, IngestStageDefinition> stageDefinitions;

    /**
     * @Description: 初始化异步导入服务依赖。
     * @Logic: 保存任务、事件、观测和阶段执行依赖，并按配置模型名创建 OCR/CHUNK/VECTOR 阶段定义。
     * @Param: ingestJobMapper 任务仓储；stageEventMapper 阶段事件仓储；reportDocumentMapper 主档仓储；ingestStageExecutor 阶段执行器；properties 异步配置；ocrModelName/chunkModelName/vectorModelName 模型名。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public ReportIngestAsyncService(IngestJobMapper ingestJobMapper,
                                    ReportIngestStageEventMapper stageEventMapper,
                                    ReportDocumentMapper reportDocumentMapper,
                                    IngestStageExecutor ingestStageExecutor,
                                    ReportIngestAsyncProperties properties,
                                    @Value("${app.ocr.model:qwen-vl-ocr-latest}") String ocrModelName,
                                    @Value("${spring.ai.openai.chat.options.model:qwen-plus-latest}") String chunkModelName,
                                    @Value("${spring.ai.openai.embedding.options.model:text-embedding-v3}") String vectorModelName) {
        this.ingestJobMapper = ingestJobMapper;
        this.stageEventMapper = stageEventMapper;
        this.reportDocumentMapper = reportDocumentMapper;
        this.ingestStageExecutor = ingestStageExecutor;
        this.properties = properties;
        this.stageDefinitions = buildStageDefinitions(ocrModelName, chunkModelName, vectorModelName);
    }

    /**
     * @Description: 提交异步导入任务并返回 jobId。
     * @Logic: 校验上传文件，写入 spool 目录，创建三阶段 PENDING 任务，并保存导入表单显式标签。
     * @Param: file 上传文件；title/source/institution/publishDate 报告元信息；themeTags/industryTags/companyTags/tickerTags 导入标签；authorTags 导入作者文本。
     * @Return: 上传响应，包含 jobId 和标题快照。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
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
                                      String tickerTags,
                                      String authorTags) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Report file is required");
        }
        String jobUid = UUID.randomUUID().toString().replace("-", "");
        String reportTitle = repairMetadataText((title == null || title.isBlank()) ? file.getOriginalFilename() : title);
        Instant now = Instant.now();
        Path spoolFile = persistUploadFile(jobUid, file);

        IngestJob job = new IngestJob();
        job.setJobUid(jobUid);
        job.setReportTitleSnapshot(reportTitle);
        job.setTitleSearchKey(normalizeTitle(reportTitle));
        job.setSource(repairMetadataText(source == null || source.isBlank() ? "uploaded" : source));
        job.setInstitution(institution == null ? null : repairMetadataText(institution));
        job.setPublishDate(publishDate);
        job.setThemeTags(normalizeOptionalTags(themeTags));
        job.setIndustryTags(normalizeOptionalTags(industryTags));
        job.setCompanyTags(normalizeOptionalTags(companyTags));
        job.setTickerTags(normalizeOptionalTags(tickerTags));
        job.setAuthorTags(normalizeOptionalTags(authorTags));
        job.setOriginalFilename(file.getOriginalFilename());
        job.setFilePath(spoolFile.toString());
        job.setOcrStatus(IngestStageStatusEnum.PENDING.code());
        job.setChunkStatus(IngestStageStatusEnum.PENDING.code());
        job.setVectorStatus(IngestStageStatusEnum.PENDING.code());
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
     * @Description: 查询单个导入任务状态。
     * @Logic: 按 jobUid 查询任务，组装三阶段状态、尝试次数、下次执行时间和最近错误摘要。
     * @Param: jobUid 任务唯一标识。
     * @Return: 任务状态响应。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
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
     * @Description: 按 reportId 查询导入阶段时间线。
     * @Logic: 从阶段事件表按时间顺序读取事件，并转换为前端响应 DTO。
     * @Param: reportId 报告 ID。
     * @Return: 阶段事件响应列表。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public List<ReportIngestStageEventRespDTO> getTimelineByReportId(Long reportId) {
        return stageEventMapper.selectTimelineByReportId(reportId).stream().map(this::toStageResp).toList();
    }

    /**
     * @Description: 按标题关键词检索导入阶段事件。
     * @Logic: 标题关键词归一化后执行模糊检索，并限制返回规模避免大结果集。
     * @Param: titleKeyword 标题关键词；limit 返回条数上限。
     * @Return: 阶段事件响应列表。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public List<ReportIngestStageEventRespDTO> searchTimelineByTitle(String titleKeyword, int limit) {
        return stageEventMapper.selectByTitleKeyword(normalizeTitle(titleKeyword), Math.max(1, Math.min(limit, 200))).stream()
                .map(this::toStageResp)
                .toList();
    }

    /**
     * @Description: 汇总当前异步导入积压与最终失败指标。
     * @Logic: 分别统计 OCR/CHUNK/VECTOR 的 PENDING 与 FAILED_FINAL 数量，供观测页面展示链路健康度。
     * @Param: 无。
     * @Return: 导入指标响应。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public IngestMetricsRespDTO getMetrics() {
        return new IngestMetricsRespDTO(
                ingestJobMapper.countByStageStatus(IngestStageEnum.OCR.code(), IngestStageStatusEnum.PENDING.code()),
                ingestJobMapper.countByStageStatus(IngestStageEnum.CHUNK.code(), IngestStageStatusEnum.PENDING.code()),
                ingestJobMapper.countByStageStatus(IngestStageEnum.VECTOR.code(), IngestStageStatusEnum.PENDING.code()),
                ingestJobMapper.countByStageStatus(IngestStageEnum.OCR.code(), IngestStageStatusEnum.FAILED_FINAL.code()),
                ingestJobMapper.countByStageStatus(IngestStageEnum.CHUNK.code(), IngestStageStatusEnum.FAILED_FINAL.code()),
                ingestJobMapper.countByStageStatus(IngestStageEnum.VECTOR.code(), IngestStageStatusEnum.FAILED_FINAL.code())
        );
    }

    /**
     * @Description: 查询研报导入观测列表。
     * @Logic: 按标题关键词和返回上限查询主档观测投影，并补充阶段状态中文文案。
     * @Param: titleKeyword 标题关键词；limit 返回条数上限。
     * @Return: 研报观测响应列表。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
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
     * @Logic: 固定延迟触发 OCR 阶段批量拉取与阶段执行。
     * @Param: 无。
     * @Return: 无（仅调度副作用）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Scheduled(fixedDelayString = "${app.report-ingest-async.fixed-delay-ms:3000}")
    public void runOcrScheduler() {
        runStage(IngestStageEnum.OCR);
    }

    /**
     * @Description: CHUNK 阶段定时调度入口。
     * @Logic: 固定延迟触发 CHUNK 阶段批量拉取与阶段执行。
     * @Param: 无。
     * @Return: 无（仅调度副作用）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Scheduled(fixedDelayString = "${app.report-ingest-async.fixed-delay-ms:3000}")
    public void runChunkScheduler() {
        runStage(IngestStageEnum.CHUNK);
    }

    /**
     * @Description: VECTOR 阶段定时调度入口。
     * @Logic: 固定延迟触发 VECTOR 阶段批量拉取与阶段执行。
     * @Param: 无。
     * @Return: 无（仅调度副作用）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Scheduled(fixedDelayString = "${app.report-ingest-async.fixed-delay-ms:3000}")
    public void runVectorScheduler() {
        runStage(IngestStageEnum.VECTOR);
    }

    /**
     * @Description: 批量执行某个阶段的可运行任务。
     * @Logic: 通过 Mapper 保持前置阶段过滤和 nextRunAt 约束，再逐个委托阶段执行模板处理。
     * @Param: stage 入库阶段枚举。
     * @Return: 无（仅推进任务状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private void runStage(IngestStageEnum stage) {
        List<IngestJob> jobs = ingestJobMapper.selectRunnableForStage(
                stage.code(),
                IngestStageStatusEnum.PENDING.code(),
                Instant.now(),
                properties.getBatchSize());
        for (IngestJob job : jobs) {
            processSingle(stage.code(), job.getJobUid());
        }
    }

    /**
     * @Description: 执行单个任务的单阶段处理。
     * @Logic: 将阶段编码解析为定义，并委托 IngestStageExecutor 处理 claim、handler、success/failure 和事件。
     * @Param: stageCode 阶段编码；jobUid 任务唯一标识。
     * @Return: 无（仅推进任务状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    void processSingle(String stageCode, String jobUid) {
        IngestStageEnum stage = IngestStageEnum.fromCode(stageCode);
        ingestStageExecutor.process(stageDefinitions.get(stage), jobUid);
    }

    /**
     * @Description: 创建阶段定义映射。
     * @Logic: 将配置中的 OCR、chat 和 embedding 模型名分别绑定到 OCR、CHUNK、VECTOR 阶段。
     * @Param: ocrModelName OCR 模型名；chunkModelName 切片模型名；vectorModelName 向量模型名。
     * @Return: 不可变阶段定义映射。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private Map<IngestStageEnum, IngestStageDefinition> buildStageDefinitions(String ocrModelName,
                                                                              String chunkModelName,
                                                                              String vectorModelName) {
        EnumMap<IngestStageEnum, IngestStageDefinition> definitions = new EnumMap<>(IngestStageEnum.class);
        definitions.put(IngestStageEnum.OCR, new IngestStageDefinition(IngestStageEnum.OCR, ocrModelName));
        definitions.put(IngestStageEnum.CHUNK, new IngestStageDefinition(IngestStageEnum.CHUNK, chunkModelName));
        definitions.put(IngestStageEnum.VECTOR, new IngestStageDefinition(IngestStageEnum.VECTOR, vectorModelName));
        return Collections.unmodifiableMap(definitions);
    }

    /**
     * @Description: 标准化可选标签输入。
     * @Logic: 空白输入统一保存为 null，非空输入修复编码并保留原始分隔结构供标签服务解析。
     * @Param: value 原始标签输入。
     * @Return: 标准化后的标签文本。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private String normalizeOptionalTags(String value) {
        return value == null || value.isBlank() ? null : repairMetadataText(value);
    }

    /**
     * @Description: 修复上传元信息中的 UTF-8 误解码乱码。
     * @Logic: null 原样返回，非空文本去除首尾空白后调用编码修复工具。
     * @Param: value 原始元信息文本。
     * @Return: 可安全展示和入库的元信息文本。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private String repairMetadataText(String value) {
        if (value == null) {
            return null;
        }
        return TextEncodingRepairUtils.repairMojibake(value.trim());
    }

    /**
     * @Description: 将上传文件持久化到本地 spool 目录。
     * @Logic: 创建目录、清洗文件名并覆盖写入目标文件；IO 异常转换为状态异常供提交链路返回。
     * @Param: jobUid 任务唯一标识；file 上传文件。
     * @Return: 落盘文件路径。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
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
     * @Description: 将事件实体映射为接口响应对象。
     * @Logic: 提取事件字段并将内部状态码转换为展示文案。
     * @Param: event 阶段事件实体。
     * @Return: 阶段事件响应 DTO。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
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
     * @Logic: 使用统一状态枚举映射，未知状态保留原值便于排障。
     * @Param: statusCode 内部状态码。
     * @Return: 中文状态文案或原始状态码。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private String statusLabel(String statusCode) {
        return IngestStageStatusEnum.toLabel(statusCode);
    }

    /**
     * @Description: 按任务 ID 查询并校验任务存在性。
     * @Logic: 未命中时抛出参数异常，避免后续查询返回空状态。
     * @Param: jobUid 任务唯一标识。
     * @Return: 任务实体。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private IngestJob requireJob(String jobUid) {
        IngestJob job = ingestJobMapper.selectByJobUid(jobUid);
        if (job == null) {
            throw new IllegalArgumentException("Ingest job not found: " + jobUid);
        }
        return job;
    }

    /**
     * @Description: 标准化标题检索键。
     * @Logic: null 回退空字符串，非空文本转小写并移除空白字符。
     * @Param: value 原始标题。
     * @Return: 标准化标题检索键。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private String normalizeTitle(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * @Description: 安全转换可空整型。
     * @Logic: null 统一回退为 0，避免响应和事件字段出现空计数。
     * @Param: value 可空整型。
     * @Return: 非空整型值。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
