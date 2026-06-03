package com.example.aimilvusweb.evaluation.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 评测 case 标准锚点实体，保存主题、行业、公司、股票代码或章节意图的期望值。
 * @Logic: 自动判分时将标准锚点与推荐响应中的 actualAnchors 比对，用于识别锚点抽取偏差。
 * @Param: 无。
 * @Return: 评测 case 标准锚点对象。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Getter
@Setter
public class EvaluationCaseAnchor {

    /** 标准锚点主键。 */
    private Long id;
    /** 所属 eval_case 表主键。 */
    private Long casePkId;
    /** 锚点类型，例如 THEME、INDUSTRY、COMPANY、TICKER 或 SECTION。 */
    private String anchorType;
    /** 锚点编码，例如 STORAGE 或 300750.SZ。 */
    private String anchorCode;
    /** 锚点展示名称，例如 储能 或 宁德时代。 */
    private String anchorName;
    /** 该锚点是否必须被系统识别。 */
    private Boolean required;
    /** 记录创建时间。 */
    private Instant createdAt;
}
