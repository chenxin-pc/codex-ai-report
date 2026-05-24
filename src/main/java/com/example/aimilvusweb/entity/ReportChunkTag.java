package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @Description: 研报子切片结构化标签实体，记录 chunk 命中的主题、行业、公司和代码标签。
 * @Logic: 标签抽取 job 写入该表，检索和 Milvus metadata 同步以该表作为可追溯主数据。
 * @Param: 详见字段注释；该类型本身无入参。
 * @Return: 作为标签落库、查询和 metadata 同步的数据载体返回。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Getter
@Setter
public class ReportChunkTag {

    /** 标签记录主键。 */
    private Long id;
    /** 标签所属研报主档 ID。 */
    private Long reportId;
    /** 标签所属子切片唯一标识。 */
    private String chunkUid;
    /** 标签类型，例如 THEME、INDUSTRY、COMPANY 或 TICKER。 */
    private String tagType;
    /** 标签编码，例如 STORAGE 或 300750.SZ。 */
    private String tagCode;
    /** 标签展示名称，例如 储能 或 宁德时代。 */
    private String tagName;
    /** 标签置信度，词库硬命中默认接近 1。 */
    private BigDecimal confidence;
    /** 标签来源，例如 DICTIONARY 或 CHUNK_AGGREGATION。 */
    private String source;
    /** 标签产生时使用的词库版本。 */
    private String dictionaryVersion;
    /** 标签创建时间。 */
    private Instant createdAt;
    /** 标签更新时间。 */
    private Instant updatedAt;
}
