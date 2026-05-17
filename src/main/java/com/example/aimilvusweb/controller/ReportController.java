package com.example.aimilvusweb.controller;

import com.example.aimilvusweb.dto.RecommendReqDTO;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.dto.RecommendStreamEventDTO;
import com.example.aimilvusweb.dto.ReportUploadRespDTO;
import com.example.aimilvusweb.service.ReportIngestService;
import com.example.aimilvusweb.service.ReportRecommendService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
/**
 * @Description: ReportController类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class ReportController {

    private final ReportIngestService reportIngestService;
    private final ReportRecommendService reportRecommendService;

    /**
     * @Description: 初始化ReportController依赖与运行所需组件。
     * @Logic: 保存入库服务与推荐服务引用，供各HTTP入口方法委托调用。
     * @Param: reportIngestService 研报入库服务；reportRecommendService 推荐分析服务。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    public ReportController(ReportIngestService reportIngestService, ReportRecommendService reportRecommendService) {
        this.reportIngestService = reportIngestService;
        this.reportRecommendService = reportRecommendService;
    }

    /**
     * @Description: 接收上传请求并委托入库服务执行OCR解析、切片与存储流程。
     * @Logic: 仅做参数接收与转发，不在Controller层编排业务细节。
     * @Param: file 上传PDF文件；title/source/institution/publishDate 报告元信息。
     * @Return: 入库结果对象，包含报告ID与切片统计。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    @PostMapping("/upload")
    public ReportUploadRespDTO upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "institution", required = false) String institution,
            @RequestParam(value = "publishDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate publishDate
    ) {
        return reportIngestService.ingest(file, title, source, institution, publishDate);
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
        return reportRecommendService.recommendStream(reqDTO.query());
    }
}
