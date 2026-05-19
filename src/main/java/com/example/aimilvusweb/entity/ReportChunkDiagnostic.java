package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 切片诊断实体，保存切片质量判定、过滤原因、范围信息及诊断文本。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:53:07
 */
@Getter
@Setter
public class ReportChunkDiagnostic {

    private Long id;
    private Long reportId;
    private String chunkUid;
    private String parentChunkUid;
    private Integer parentIndex;
    private Integer chunkIndexInParent;
    private String chunkType;
    private String sectionPath;
    private Integer tokenCount;
    private Integer startParagraphId;
    private Integer endParagraphId;
    private Integer startPageNumber;
    private Integer endPageNumber;
    private Boolean kept;
    private String filterReason;
    private String diagnostics;
    private String chunkText;
    private Instant createdAt;
}
