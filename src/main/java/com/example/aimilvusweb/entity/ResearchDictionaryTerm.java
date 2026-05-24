package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * @Description: 投研结构化词库匹配项实体，承载主题、行业、公司和代码词条的统一读取结果。
 * @Logic: MyBatis 从多张词库表查询后映射为该对象，词库快照服务再构建内存匹配索引。
 * @Param: 详见字段注释；该类型本身无入参。
 * @Return: 作为词库加载和 query/chunk 匹配的数据载体返回。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Getter
@Setter
public class ResearchDictionaryTerm {

    /** 标签类型，例如 THEME、INDUSTRY、COMPANY 或 TICKER。 */
    private String tagType;
    /** 标签编码，例如 STORAGE、POWER_EQUIPMENT 或 300750.SZ。 */
    private String tagCode;
    /** 标签展示名称，例如 储能、 电力设备 或 宁德时代。 */
    private String tagName;
    /** 原始词条文本，用于解释命中来源。 */
    private String termText;
    /** 规范化词条文本，用于去空白和大小写后的快速匹配。 */
    private String normalizedTerm;
    /** 词条关系类型，例如 REQUIRED、ALIAS 或 EXCLUDE。 */
    private String relationType;
    /** 词条权重，用于后续覆盖分或重排分计算。 */
    private BigDecimal weight;
    /** 词库版本，记录当前词条来自哪个 ACTIVE 快照。 */
    private String dictionaryVersion;
}
