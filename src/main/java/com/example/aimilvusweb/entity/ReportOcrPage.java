package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: OCR 页结果实体，保存每页原文、清洗文本与诊断信息。
 * @author: cx
 * @Date: 2026-05-17 10:53:07
 */
@Getter
@Setter
public class ReportOcrPage {

    private Long id;
    private Long reportId;
    private Integer pageNumber;
    private String rawText;
    private String cleanedText;
    private String diagnostics;
    private Instant createdAt;
}
