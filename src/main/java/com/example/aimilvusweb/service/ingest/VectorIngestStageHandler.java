package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.entity.IngestJob;
import com.example.aimilvusweb.enums.IngestStageEnum;
import com.example.aimilvusweb.service.ReportIngestService;
import org.springframework.stereotype.Component;

/**
 * @Description: VECTOR 入库阶段 handler，负责将已落库 CHILD chunk 写入 Milvus。
 * @Logic: 校验 reportId 后委托 ReportIngestService 执行向量阶段，保持只处理未向量化 CHILD 的幂等语义。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Component
public class VectorIngestStageHandler implements IngestStageHandler {

    /** 导入执行服务，复用异步 VECTOR 阶段能力。 */
    private final ReportIngestService reportIngestService;

    /**
     * @Description: 初始化 VECTOR 阶段 handler。
     * @Logic: 保存入库执行服务，供 execute 阶段委托向量入库。
     * @Param: reportIngestService 入库执行服务。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public VectorIngestStageHandler(ReportIngestService reportIngestService) {
        this.reportIngestService = reportIngestService;
    }

    /**
     * @Description: 返回 VECTOR 阶段枚举。
     * @Logic: 固定返回 VECTOR，用于阶段执行器路由。
     * @Param: 无。
     * @Return: VECTOR 阶段枚举。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Override
    public IngestStageEnum stage() {
        return IngestStageEnum.VECTOR;
    }

    /**
     * @Description: 执行 VECTOR 阶段业务动作。
     * @Logic: reportId 缺失时抛出明确异常；存在时委托入库服务写入 Milvus 并返回本次入库 CHILD 数量。
     * @Param: job 导入任务。
     * @Return: 本阶段向量化的 CHILD chunk 数量。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    @Override
    public int execute(IngestJob job) {
        if (job.getReportId() == null) {
            throw new IllegalStateException("Missing reportId for vector stage");
        }
        return reportIngestService.ingestVectorStage(job.getReportId());
    }
}
