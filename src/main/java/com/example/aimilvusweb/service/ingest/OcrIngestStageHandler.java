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
 * @Param: 无。
 * @Return: OCR 阶段处理器，供阶段执行器按阶段枚举调用。
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
        // 保存导入服务引用，OCR 阶段主流程由它完成。
        this.reportIngestService = reportIngestService;
        // 保存任务 Mapper，OCR 成功后需要把 reportId 写回 ingest_job。
        this.ingestJobMapper = ingestJobMapper;
        // 保存报告标签服务，上传表单带入的标签在 reportId 生成后落库。
        this.reportDocumentTagService = reportDocumentTagService;
        // 保存词库快照服务，显式标签落库需要绑定当前词库版本。
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
        // 固定声明当前 handler 只处理 OCR 阶段。
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
        // 将 spool 文件路径包装成 MultipartFile，复用同步导入阶段已有入参形态。
        MultipartFile file = new StoredPdfMultipartFile(job.getOriginalFilename(), Path.of(job.getFilePath()));
        // 执行 OCR 主链路：创建/复用 report_document，写入 OCR 页和段落 atom。
        Long reportId = reportIngestService.ingestOcrStage(file,
                job.getReportTitleSnapshot(),
                job.getSource(),
                job.getInstitution(),
                job.getPublishDate(),
                job.getAuthorTags(),
                job.getReportId());
        // 将 OCR 阶段生成的 reportId 绑定回导入任务，供后续 CHUNK/VECTOR 使用。
        ingestJobMapper.bindReportId(job.getJobUid(), reportId, Instant.now());
        // 按上传元数据刷新报告级显式标签，保证导入任务标签进入检索 metadata。
        reportDocumentTagService.refreshFromImportMetadata(reportId,
                taxonomySnapshotService.currentSnapshot().dictionaryVersion(),
                job.getThemeTags(),
                job.getIndustryTags(),
                job.getCompanyTags(),
                job.getTickerTags());
        // 同步更新当前任务对象，后续成功事件可直接读取 reportId。
        job.setReportId(reportId);
        // OCR 阶段以“绑定一篇报告”为输出单位，固定返回 1。
        return 1;
    }
}
