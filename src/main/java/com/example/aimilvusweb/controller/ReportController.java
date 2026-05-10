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
public class ReportController {

    private final ReportIngestService reportIngestService;
    private final ReportRecommendService reportRecommendService;

    public ReportController(ReportIngestService reportIngestService, ReportRecommendService reportRecommendService) {
        this.reportIngestService = reportIngestService;
        this.reportRecommendService = reportRecommendService;
    }

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

    @PostMapping("/recommend")
    public RecommendRespDTO recommend(@Valid @RequestBody RecommendReqDTO reqDTO) {
        return reportRecommendService.recommend(reqDTO.query());
    }
}
