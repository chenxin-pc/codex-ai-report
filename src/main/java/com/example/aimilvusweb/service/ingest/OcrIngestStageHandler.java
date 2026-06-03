package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.entity.IngestJob;
import com.example.aimilvusweb.enums.IngestStageEnum;
import com.example.aimilvusweb.repository.IngestJobMapper;
import com.example.aimilvusweb.service.tag.ReportDocumentTagService;
import com.example.aimilvusweb.service.ingest.ReportIngestService;
import com.example.aimilvusweb.service.taxonomy.ResearchTaxonomySnapshotService;
import com.example.aimilvusweb.service.ingest.StoredPdfMultipartFile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.Instant;

/**
 * @Description: OCR 入库阶段 handler，负责将暂存 PDF 交给 OCR 阶段入库并绑定 reportId。
 * @Logic: 包装 spool 文件为 MultipartFile，调用 ReportIngestService 写入主档/OCR/段落数据，再刷新导入元数据标签。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Component
public class OcrIngestStageHandler implements IngestStageHandler {

    /** 导入执行服务，复用同步 OCR 阶段能力。 */
    private final ReportIngestService reportIngestService;
    /** 任务 Mapper，用于 OCR 成功后绑定 reportId。 */
    private final IngestJobMapper ingestJobMapper;
    /** 报告标签服务，用于保存上传表单显式标签。 */
    private final ReportDocumentTagService reportDocumentTagService;
    /** 词库快照服务，用于记录导入标签的词库版本。 */
    private final ResearchTaxonomySnapshotService taxonomySnapshotService;

    /**
     * @Description: 初始化 OCR 阶段 handler。
     * @Logic: 保存 OCR 阶段所需的入库服务、任务 Mapper、标签服务和词库快照服务。
     * @Param: reportIngestService 入库执行服务；ingestJobMapper 任务 Mapper；reportDocumentTagService 标签服务；taxonomySnapshotService 词库快照服务。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public OcrIngestStageHandler(ReportIngestService reportIngestService,
                                 IngestJobMapper ingestJobMapper,
                                 ReportDocumentTagService reportDocumentTagService,
                                 ResearchTaxonomySnapshotService taxonomySnapshotService) {
        this.reportIngestService = reportIngestService;
        this.ingestJobMapper = ingestJobMapper;
        this.reportDocumentTagService = reportDocumentTagService;
        this.taxonomySnapshotService = taxonomySnapshotService;
    }

    /**
     * @Description: 返回 OCR 阶段枚举。
     * @Logic: 固定返回 OCR，用于阶段执行器路由。
     * @Param: 无。
     * @Return: OCR 阶段枚举。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Override
    public IngestStageEnum stage() {
        return IngestStageEnum.OCR;
    }

    /**
     * @Description: 执行 OCR 阶段业务动作。
     * @Logic: 读取暂存文件创建 report 与 OCR 质量数据；成功后绑定 reportId 并刷新导入元数据标签。
     * @Param: job 导入任务。
     * @Return: OCR 阶段输出数量，固定为 1 表示绑定一篇报告。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Override
    public int execute(IngestJob job) throws Exception {
        MultipartFile file = new StoredPdfMultipartFile(job.getOriginalFilename(), Path.of(job.getFilePath()));
        Long reportId = reportIngestService.ingestOcrStage(file,
                job.getReportTitleSnapshot(),
                job.getSource(),
                job.getInstitution(),
                job.getPublishDate(),
                job.getAuthorTags(),
                job.getReportId());
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
}
