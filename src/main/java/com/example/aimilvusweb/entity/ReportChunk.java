package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 研报切片实体，记录父子切片关系、文本内容、页码范围、过滤与向量化状态。
 * @author: cx
 * @Date: 2026-05-17 10:53:07
 */
@Getter
@Setter
public class ReportChunk {

    private Long id;
    private Long reportId;
    private Integer chunkIndex;
    private String chunkUid;
    private String parentChunkUid;
    private String chunkType;
    private String sectionPath;
    private String chunkText;
    private Integer tokenCount;
    private Integer pageNumber;
    private Integer startParagraphId;
    private Integer endParagraphId;
    private Integer startPageNumber;
    private Integer endPageNumber;
    private String filterReason;
    private String diagnostics;
    private Boolean vectorStored;
    private Instant createdAt;
}
