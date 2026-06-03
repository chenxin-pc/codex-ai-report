package com.example.aimilvusweb.evaluation.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: Eval case 禁止命中项实体，记录召回或生成中不应出现的主题、公司、关键词或 chunk。
 * @Logic: 自动判分扫描 retrieved context 和 responseText，命中 forbiddenValue 时标记污染或禁用结论。
 * @Param: 无。
 * @Return: Eval case 禁止命中项对象。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Getter
@Setter
public class EvaluationCaseForbiddenContext {

    /** 禁止项主键。 */
    private Long id;
    /** 所属 eval_case 表主键。 */
    private Long casePkId;
    /** 禁止项类型，例如 THEME、INDUSTRY、COMPANY、TICKER、KEYWORD 或 CHUNK。 */
    private String forbiddenType;
    /** 禁止命中的编码、名称、关键词或 chunkUid。 */
    private String forbiddenValue;
    /** 记录创建时间。 */
    private Instant createdAt;
}
