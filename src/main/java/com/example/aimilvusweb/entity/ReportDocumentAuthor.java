package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 研报作者实体，保存导入阶段解析或录入的作者原文与规范化名称。
 * @Logic: MySQL 作为作者元数据事实来源，Milvus 仅从该表投影 author metadata。
 * @Param: 无。
 * @Return: 无（仅数据载体）。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
@Getter
@Setter
public class ReportDocumentAuthor {

    /** 作者记录自增主键，用于数据库定位单条作者记录。 */
    private Long id;
    /** 关联的研报主档 ID，来源于 report_document.id。 */
    private Long reportId;
    /** 作者展示名称，保留导入时的可读文本。 */
    private String authorName;
    /** 作者规范化名称，用于去重、过滤和 Milvus author metadata。 */
    private String normalizedAuthorName;
    /** 作者在导入输入中的顺序，用于多作者稳定展示和投影。 */
    private Integer authorOrder;
    /** 作者记录创建时间，用于追溯导入批次。 */
    private Instant createdAt;
}
