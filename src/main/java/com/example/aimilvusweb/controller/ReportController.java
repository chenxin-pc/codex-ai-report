package com.example.aimilvusweb.controller;

import com.example.aimilvusweb.dto.RecommendReqDTO;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.dto.RecommendStreamEventDTO;
import com.example.aimilvusweb.dto.IngestMetricsRespDTO;
import com.example.aimilvusweb.dto.IngestJobStatusRespDTO;
import com.example.aimilvusweb.dto.ReportIngestStageEventRespDTO;
import com.example.aimilvusweb.dto.ReportIngestChainObservationRespDTO;
import com.example.aimilvusweb.dto.ReportChunkObservationRespDTO;
import com.example.aimilvusweb.dto.ReportObservationRespDTO;
import com.example.aimilvusweb.dto.ReportUploadRespDTO;
import com.example.aimilvusweb.service.ReportIngestAsyncService;
import com.example.aimilvusweb.service.ReportQualityQueryService;
import com.example.aimilvusweb.service.ReportRecommendService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.List;

/**
 * @Description: ReportController类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportIngestAsyncService reportIngestAsyncService;
    private final ReportRecommendService reportRecommendService;
    private final ReportQualityQueryService reportQualityQueryService;

    /**
     * @Description: 初始化ReportController依赖与运行所需组件。
     * @Logic: 保存入库服务与推荐服务引用，供各HTTP入口方法委托调用。
     * @Param: reportIngestService 研报入库服务；reportRecommendService 推荐分析服务。
     * @Return: 无（仅初始化对象状态）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    public ReportController(ReportIngestAsyncService reportIngestAsyncService,
                            ReportRecommendService reportRecommendService,
                            ReportQualityQueryService reportQualityQueryService) {
        this.reportIngestAsyncService = reportIngestAsyncService;
        this.reportRecommendService = reportRecommendService;
        this.reportQualityQueryService = reportQualityQueryService;
    }

    /**
     * @Description: 接收上传请求并委托入库服务执行OCR解析、切片与存储流程。
     * @Logic: 仅做参数接收与转发，不在Controller层编排业务细节。
     * @Param: file 上传PDF文件；title/source/institution/publishDate 报告元信息。
     * @Return: 入库结果对象，包含报告ID与切片统计。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    @PostMapping("/upload")
    public ReportUploadRespDTO upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "institution", required = false) String institution,
            @RequestParam(value = "publishDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate publishDate,
            @RequestParam(value = "themeTags", required = false) String themeTags,
            @RequestParam(value = "industryTags", required = false) String industryTags,
            @RequestParam(value = "companyTags", required = false) String companyTags,
            @RequestParam(value = "tickerTags", required = false) String tickerTags
    ) {
        return reportIngestAsyncService.submit(file, title, source, institution, publishDate, themeTags, industryTags, companyTags, tickerTags);
    }

    /**
     * @Description: 查询异步导入任务状态。
     * @Logic: 根据 jobId 调用异步服务返回 OCR/Chunk/Vector 三阶段状态与重试信息。
     * @Param: jobId 导入任务唯一标识。
     * @Return: 任务状态响应对象。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    @GetMapping("/ingest-jobs/{jobId}")
    public IngestJobStatusRespDTO ingestJobStatus(@PathVariable("jobId") String jobId) {
        return reportIngestAsyncService.getJobStatus(jobId);
    }

    /**
     * @Description: 查询导入阶段事件时间线。
     * @Logic: 优先按 reportId 精确查询；未传 reportId 时按标题关键词模糊检索并限制返回条数。
     * @Param: reportId 报告ID；titleKeyword 标题关键词；limit 返回上限。
     * @Return: 阶段事件列表。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    @GetMapping("/ingest-stage-events")
    public List<ReportIngestStageEventRespDTO> ingestStageEvents(
            @RequestParam(value = "reportId", required = false) Long reportId,
            @RequestParam(value = "titleKeyword", required = false) String titleKeyword,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit
    ) {
        if (reportId != null) {
            return reportIngestAsyncService.getTimelineByReportId(reportId);
        }
        return reportIngestAsyncService.searchTimelineByTitle(titleKeyword == null ? "" : titleKeyword, limit);
    }

    /**
     * @Description: 查询导入链路积压与最终失败指标。
     * @Logic: 委托异步服务聚合各阶段 pending 与 failed_final 数量。
     * @Param: 无。
     * @Return: 导入链路指标对象。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    @GetMapping("/ingest-metrics")
    public IngestMetricsRespDTO ingestMetrics() {
        return reportIngestAsyncService.getMetrics();
    }

    /**
     * @Description: 查询研报观测列表。
     * @Logic: 按标题关键词筛选并返回每篇研报的最新导入状态，供前端列表与观测入口使用。
     * @Param: titleKeyword 标题关键词；limit 返回上限。
     * @Return: 研报观测列表。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:59:00
     */
    @GetMapping("/observations")
    public List<ReportObservationRespDTO> observations(
            @RequestParam(value = "titleKeyword", required = false) String titleKeyword,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
    ) {
        return reportIngestAsyncService.listObservations(titleKeyword, limit);
    }

    /**
     * @Description: 查询指定研报的切片前后对照数据。
     * @Logic: 按 reportId 返回切片前原文与切片后文本，供观测页面核对切片质量。
     * @Param: reportId 研报ID。
     * @Return: 切片观测响应。
     * @author: cx
     * @Date: 2026-05-20 23:40:00
     */
    @GetMapping("/{reportId}/chunk-observation")
    public ReportChunkObservationRespDTO chunkObservation(@PathVariable("reportId") Long reportId) {
        return reportQualityQueryService.getChunkObservationByReportId(reportId);
    }

    /**
     * @Description: 查询指定研报的开发者导入链路解释视图。
     * @Logic: Controller 只接收 reportId 并委托质量查询服务聚合报告主档、阶段事件、OCR、切片和向量状态。
     * @Param: reportId 研报ID。
     * @Return: 导入链路解释响应，包含阶段摘要、操作说明、前后状态、影响点和可展开明细。
     * @author: cx
     * @Date: 2026-05-30 00:00:00
     */
    @GetMapping("/{reportId}/ingest-chain-observation")
    public ReportIngestChainObservationRespDTO ingestChainObservation(@PathVariable("reportId") Long reportId) {
        return reportQualityQueryService.getIngestChainObservation(reportId);
    }

    /**
     * @Description: 接收同步推荐请求并返回结构化推荐结果。
     * @Logic: 从请求体提取query后直接委托推荐服务，同步返回JSON对象。
     * @Param: reqDTO 推荐请求对象，包含用户query。
     * @Return: 结构化推荐响应，包含分析、建议、风险与引用。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    @PostMapping("/recommend")
    public RecommendRespDTO recommend(@Valid @RequestBody RecommendReqDTO reqDTO) {
        // 从请求 DTO 中取出 query，并委托推荐服务执行输入判定、召回、降级和同步响应构建。
        return reportRecommendService.recommend(reqDTO.query());
    }

    /**
     * @Description: 生成推荐结果并以 SSE 流式返回推荐响应。
     * @Logic: 从请求体提取query后委托推荐服务输出SSE事件流，事件包含状态、证据、增量文本与结束标记。
     * @Param: reqDTO 推荐请求对象，包含用户query。
     * @Return: SSE事件流响应，按event字段区分status/evidence/delta/done/error。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    @PostMapping(value = "/recommend/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<RecommendStreamEventDTO>> recommendStream(@Valid @RequestBody RecommendReqDTO reqDTO) {
        // 从请求 DTO 中取出 query，并委托推荐服务返回包含状态、证据、增量和完成事件的 SSE 流。
        return reportRecommendService.recommendStream(reqDTO.query());
    }
}
