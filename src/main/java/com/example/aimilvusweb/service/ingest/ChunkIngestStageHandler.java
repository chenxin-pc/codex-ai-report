package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.entity.IngestJob;
import com.example.aimilvusweb.enums.IngestStageEnum;
import com.example.aimilvusweb.service.ingest.ReportIngestService;
import org.springframework.stereotype.Component;

/**
 * @Description: CHUNK 入库阶段 handler，负责调用语义切片阶段生成并持久化 PARENT/CHILD chunk。
 * @Logic: 校验 OCR 阶段已回填 reportId，再委托 ReportIngestService 读取段落 atom 并执行切片落库。
 * @Param: 无。
 * @Return: CHUNK 阶段处理器，供阶段执行器按阶段枚举调用。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Component
public class ChunkIngestStageHandler implements IngestStageHandler {

    /** 导入执行服务，复用异步 CHUNK 阶段能力。 */
    private final ReportIngestService reportIngestService;

    /**
     * @Description: 初始化 CHUNK 阶段 handler。
     * @Logic: 保存入库执行服务，供 execute 阶段委托切片落库。
     * @Param: reportIngestService 入库执行服务。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public ChunkIngestStageHandler(ReportIngestService reportIngestService) {
        // 保存导入服务引用，execute 时复用其中的 chunk 阶段幂等逻辑。
        this.reportIngestService = reportIngestService;
    }

    /**
     * @Description: 返回 CHUNK 阶段枚举。
     * @Logic: 固定返回 CHUNK，用于阶段执行器路由。
     * @Param: 无。
     * @Return: CHUNK 阶段枚举。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Override
    public IngestStageEnum stage() {
        // 固定声明当前 handler 只处理 CHUNK 阶段。
        return IngestStageEnum.CHUNK;
    }

    /**
     * @Description: 执行 CHUNK 阶段业务动作。
     * @Logic: reportId 缺失时抛出明确异常；存在时委托入库服务生成 chunk 并返回 CHILD 数量。
     * @Param: job 导入任务。
     * @Return: 本阶段生成或复用的 CHILD chunk 数量。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Override
    public int execute(IngestJob job) {
        // CHUNK 阶段依赖 OCR 阶段生成 reportId，缺失时说明前置链路未完成。
        if (job.getReportId() == null) {
            throw new IllegalStateException("Missing reportId for chunk stage");
        }
        // 委托导入服务从段落 atom 生成 PARENT/CHILD chunk，并返回 CHILD 数量。
        return reportIngestService.ingestChunkStage(job.getReportId());
    }
}
