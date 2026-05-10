package com.example.aimilvusweb.entity;

import java.time.Instant;

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
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReportId() {
        return reportId;
    }

    public void setReportId(Long reportId) {
        this.reportId = reportId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getChunkUid() {
        return chunkUid;
    }

    public void setChunkUid(String chunkUid) {
        this.chunkUid = chunkUid;
    }

    public String getParentChunkUid() {
        return parentChunkUid;
    }

    public void setParentChunkUid(String parentChunkUid) {
        this.parentChunkUid = parentChunkUid;
    }

    public String getChunkType() {
        return chunkType;
    }

    public void setChunkType(String chunkType) {
        this.chunkType = chunkType;
    }

    public String getSectionPath() {
        return sectionPath;
    }

    public void setSectionPath(String sectionPath) {
        this.sectionPath = sectionPath;
    }

    public String getChunkText() {
        return chunkText;
    }

    public void setChunkText(String chunkText) {
        this.chunkText = chunkText;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
