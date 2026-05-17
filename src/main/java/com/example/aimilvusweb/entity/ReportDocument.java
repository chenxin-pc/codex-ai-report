package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * @Description: 研报主档实体，保存标题、来源机构、发布日期与创建时间等基础信息。
 * @author: cx
 * @Date: 2026-05-17 10:53:07
 */
@Getter
@Setter
public class ReportDocument {

    private Long id;
    private String title;
    private String source;
    private String institution;
    private LocalDate publishDate;
    private Instant createdAt;
}
