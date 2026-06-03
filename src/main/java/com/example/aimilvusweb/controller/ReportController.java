package com.example.aimilvusweb.controller;

import com.example.aimilvusweb.dto.IngestJobStatusRespDTO;
import com.example.aimilvusweb.dto.IngestMetricsRespDTO;
import com.example.aimilvusweb.dto.RecommendReqDTO;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.dto.RecommendStreamEventDTO;
import com.example.aimilvusweb.dto.ReportChunkObservationRespDTO;
import com.example.aimilvusweb.dto.ReportIngestChainObservationRespDTO;
import com.example.aimilvusweb.dto.ReportIngestDataResetRespDTO;
import com.example.aimilvusweb.dto.ReportIngestStageEventRespDTO;
import com.example.aimilvusweb.dto.ReportObservationRespDTO;
import com.example.aimilvusweb.dto.ReportUploadRespDTO;
import com.example.aimilvusweb.service.ingest.ReportIngestAsyncService;
import com.example.aimilvusweb.service.ingest.ReportIngestDataResetService;
import com.example.aimilvusweb.service.quality.ReportQualityQueryService;
import com.example.aimilvusweb.service.recommendation.ReportRecommendService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.List;

/**
 * @Description: 研报 HTTP 控制器，暴露上传、导入观测、清空导入域和推荐分析接口。
 * @Logic: Controller 只做参数接收和服务转发，具体导入、清空、召回与推荐逻辑全部下沉到 Service。
 * @Param: 无。
 * @Return: 无（Spring MVC 根据各接口返回值序列化响应）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    /** 异步导入服务，用于创建导入任务、查询任务状态和导入观测数据。 */
    private final ReportIngestAsyncService reportIngestAsyncService;
    /** 推荐服务，用于同步和流式生成研报推荐分析。 */
    private final ReportRecommendService reportRecommendService;
    /** 导入质量查询服务，用于返回切片观测和导入链路解释视图。 */
    private final ReportQualityQueryService reportQualityQueryService;
    /** 导入域清空服务，用于显式删除可重建历史研报数据。 */
    private final ReportIngestDataResetService reportIngestDataResetService;

    /**
     * @Description: 初始化研报 HTTP 控制器依赖。
     * @Logic: 保存异步导入、推荐、质量查询和导入域清空服务引用，供接口方法委托调用。
     * @Param: reportIngestAsyncService 异步导入服务；reportRecommendService 推荐分析服务；reportQualityQueryService 质量查询服务；reportIngestDataResetService 导入域清空服务。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    public ReportController(ReportIngestAsyncService reportIngestAsyncService,
                            ReportRecommendService reportRecommendService,
                            ReportQualityQueryService reportQualityQueryService,
                            ReportIngestDataResetService reportIngestDataResetService) {
        this.reportIngestAsyncService = reportIngestAsyncService;
        this.reportRecommendService = reportRecommendService;
        this.reportQualityQueryService = reportQualityQueryService;
        this.reportIngestDataResetService = reportIngestDataResetService;
    }

    /**
     * @Description: 接收上传请求并创建异步研报导入任务。
     * @Logic: 接收文件、报告元信息、显式标签和作者字段后委托异步导入服务保存任务快照。
     * @Param: file 上传 PDF 文件；title/source/institution/publishDate 报告元信息；themeTags/industryTags/companyTags/tickerTags 显式标签；authors 研报作者文本。
     * @Return: 入库受理结果，包含 jobId、标题快照和提示信息。
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
            @RequestParam(value = "tickerTags", required = false) String tickerTags,
            @RequestParam(value = "authors", required = false) String authors
    ) {
        return reportIngestAsyncService.submit(file, title, source, institution, publishDate, themeTags, industryTags, companyTags, tickerTags, authors);
    }

    /**
     * @Description: 查询异步导入任务状态。
     * @Logic: 根据 jobId 调用异步服务返回 OCR/Chunk/Vector 三阶段状态与重试信息。
     * @Param: jobId 导入任务唯一标识。
     * @Return: 任务状态响应对象。
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
     * @author: cx
     * @Date: 2026-05-19 23:40:00
     */
    @GetMapping("/ingest-metrics")
    public IngestMetricsRespDTO ingestMetrics() {
        return reportIngestAsyncService.getMetrics();
    }

    /**
     * @Description: 显式清空研报导入域历史数据。
     * @Logic: 仅当 confirm 参数等于固定确认短语时委托清空服务删除 MySQL 导入域数据和 Milvus collection。
     * @Param: confirm 清空确认短语。
     * @Return: 清空结果响应，包含各表删除行数和 Milvus 删除状态。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @PostMapping("/ingest-data/reset")
    public ReportIngestDataResetRespDTO resetIngestData(@RequestParam("confirm") String confirm) {
        // 委托服务层执行确认短语校验、MySQL 删除和 Milvus collection 删除。
        return reportIngestDataResetService.reset(confirm);
    }

    /**
     * @Description: 查询研报观测列表。
     * @Logic: 按标题关键词筛选并返回每篇研报的最新导入状态，供前端列表与观测入口使用。
     * @Param: titleKeyword 标题关键词；limit 返回上限。
     * @Return: 研报观测列表。
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
