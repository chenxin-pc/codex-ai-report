package com.example.aimilvusweb.evaluation.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: Eval case 与标准证据的关联实体，记录相关等级和必召回标记。
 * @Logic: 自动判分根据该关联判断 FINAL 检索证据是否命中标准 chunk 或 parent context。
 * @Param: 无。
 * @Return: Eval case 标准证据关联对象。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Getter
@Setter
public class EvaluationCaseReferenceContext {

    /** 关联主键。 */
    private Long id;
    /** 所属 eval_case 表主键。 */
    private Long casePkId;
    /** 关联的标准证据主键。 */
    private Long referenceContextId;
    /** 人工标注相关等级，数值越大表示越关键。 */
    private Integer relevanceLevel;
    /** 是否为该 case 的必需证据。 */
    private Boolean required;
    /** 关联备注，记录标注说明或边界条件。 */
    private String notes;
    /** 记录创建时间。 */
    private Instant createdAt;
}
