package com.example.aimilvusweb.evaluation.dto;

import com.example.aimilvusweb.evaluation.entity.EvaluationCase;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseAnchor;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseForbiddenContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationReferenceContext;

import java.util.List;

/**
 * @Description: Eval case 聚合 DTO，集中承载 case 本体、标准锚点、标准证据和禁止项。
 * @Logic: 数据集服务按 case 查询后组装该对象，运行服务和导出脚本基于该聚合对象执行判分。
 * @Param: evaluationCase case 主体；anchors 标准锚点；referenceContexts 标准证据；forbiddenContexts 禁止命中项。
 * @Return: Eval case 聚合视图。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
public record EvaluationCaseBundleDTO(
        /** Eval case 主体记录。 */
        EvaluationCase evaluationCase,
        /** 标准锚点列表，默认空集合。 */
        List<EvaluationCaseAnchor> anchors,
        /** 标准证据列表，默认空集合。 */
        List<EvaluationReferenceContext> referenceContexts,
        /** 禁止命中项列表，默认空集合。 */
        List<EvaluationCaseForbiddenContext> forbiddenContexts
) {
}
