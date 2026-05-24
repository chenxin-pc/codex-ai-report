package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @Description: 研报报告级结构化标签实体，记录导入后确定的父标签。
 * @Logic: 报告级标签用于限定查询报告集合，chunk 级标签继续用于证据覆盖判断。
 * @Param: 详见字段注释；该类型本身无入参。
 * @Return: 作为报告级标签落库、查询和 metadata 同步的数据载体返回。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Getter
@Setter
public class ReportDocumentTag {

    /** 标签记录主键。 */
    private Long id;
    /** 标签所属研报主档 ID。 */
    private Long reportId;
    /** 标签类型，例如 THEME、INDUSTRY、COMPANY 或 TICKER。 */
    private String tagType;
    /** 标签编码，例如 STORAGE 或 300750.SZ。 */
    private String tagCode;
    /** 标签展示名称，例如 储能 或 宁德时代。 */
    private String tagName;
    /** 标签置信度，聚合标签默认取来源标签最高置信度。 */
    private BigDecimal confidence;
    /** 标签来源，例如 CHUNK_AGGREGATION 或 IMPORT_METADATA。 */
    private String source;
    /** 标签产生时使用的词库版本。 */
    private String dictionaryVersion;
    /** 标签创建时间。 */
    private Instant createdAt;
    /** 标签更新时间。 */
    private Instant updatedAt;
}
