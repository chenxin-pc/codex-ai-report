package com.example.aimilvusweb.entity;

import java.time.Instant;

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

    public Integer getParentIndex() {
        return parentIndex;
    }

    public void setParentIndex(Integer parentIndex) {
        this.parentIndex = parentIndex;
    }

    public Integer getChunkIndexInParent() {
        return chunkIndexInParent;
    }

    public void setChunkIndexInParent(Integer chunkIndexInParent) {
        this.chunkIndexInParent = chunkIndexInParent;
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

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public Integer getStartParagraphId() {
        return startParagraphId;
    }

    public void setStartParagraphId(Integer startParagraphId) {
        this.startParagraphId = startParagraphId;
    }

    public Integer getEndParagraphId() {
        return endParagraphId;
    }

    public void setEndParagraphId(Integer endParagraphId) {
        this.endParagraphId = endParagraphId;
    }

    public Integer getStartPageNumber() {
        return startPageNumber;
    }

    public void setStartPageNumber(Integer startPageNumber) {
        this.startPageNumber = startPageNumber;
    }

    public Integer getEndPageNumber() {
        return endPageNumber;
    }

    public void setEndPageNumber(Integer endPageNumber) {
        this.endPageNumber = endPageNumber;
    }

    public Boolean getKept() {
        return kept;
    }

    public void setKept(Boolean kept) {
        this.kept = kept;
    }

    public String getFilterReason() {
        return filterReason;
    }

    public void setFilterReason(String filterReason) {
        this.filterReason = filterReason;
    }

    public String getDiagnostics() {
        return diagnostics;
    }

    public void setDiagnostics(String diagnostics) {
        this.diagnostics = diagnostics;
    }

    public String getChunkText() {
        return chunkText;
    }

    public void setChunkText(String chunkText) {
        this.chunkText = chunkText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
