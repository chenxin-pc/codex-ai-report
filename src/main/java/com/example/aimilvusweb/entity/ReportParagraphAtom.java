package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 段落原子实体，保存段落编号、所属页码、文本内容与质量诊断信息。
 * @author: cx
 * @Date: 2026-05-17 10:53:07
 */
@Getter
@Setter
public class ReportParagraphAtom {

    private Long id;
    private Long reportId;
    private Integer paragraphId;
    private Integer pageNumber;
    private String sectionPath;
    private String paragraphText;
    private Integer tokenCount;
    private String diagnostics;
    private Instant createdAt;
}
