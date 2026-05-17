package com.example.aimilvusweb.controller;

import com.example.aimilvusweb.dto.RecommendReqDTO;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.dto.ReportUploadRespDTO;
import com.example.aimilvusweb.service.ReportIngestService;
import com.example.aimilvusweb.service.ReportRecommendService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public ReportController(ReportIngestService reportIngestService, ReportRecommendService reportRecommendService) {
        this.reportIngestService = reportIngestService;
        this.reportRecommendService = reportRecommendService;
    }

    /**
     * @Description: 执行upload相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
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
     * @Description: 生成推荐结果并返回推荐响应。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    @PostMapping("/recommend")
    public RecommendRespDTO recommend(@Valid @RequestBody RecommendReqDTO reqDTO) {
        return reportRecommendService.recommend(reqDTO.query());
    }
}
