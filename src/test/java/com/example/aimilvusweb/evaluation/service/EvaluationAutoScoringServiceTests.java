package com.example.aimilvusweb.evaluation.service;

import com.example.aimilvusweb.evaluation.dto.EvaluationAutoScoreRespDTO;
import com.example.aimilvusweb.evaluation.dto.EvaluationCaseBundleDTO;
import com.example.aimilvusweb.evaluation.entity.EvaluationCase;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseAnchor;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseForbiddenContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseRun;
import com.example.aimilvusweb.evaluation.entity.EvaluationReferenceContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationRetrievedContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * @Description: Eval case 自动判分测试，验证标准证据命中、漏召回、禁止证据和禁止结论识别。
 * @Logic: 构造纯内存 case/run/context 对象调用判分服务，不依赖数据库或推荐接口。
 * @Param: 详见测试方法。
 * @Return: 无。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
class EvaluationAutoScoringServiceTests {

    @Test
    void shouldPassWhenIntentAnchorOutputAndReferenceMatch() {
        EvaluationAutoScoringService service = new EvaluationAutoScoringService();
        EvaluationCaseRun caseRun = caseRun("THEME_RESEARCH", "[\"STORAGE\"]", "L2_THEME_RESEARCH", "储能需求增长");

        EvaluationAutoScoreRespDTO score = service.score(bundle("chunk-1"), caseRun, List.of(context("chunk-1", "parent-1", "储能需求增长")));

        Assertions.assertTrue(score.intentMatch());
        Assertions.assertTrue(score.anchorMatch());
        Assertions.assertTrue(score.outputLevelMatch());
        Assertions.assertTrue(score.referenceContextHit());
        Assertions.assertFalse(score.forbiddenContextHit());
        Assertions.assertFalse(score.needsManualReview());
    }

    @Test
    void shouldFlagMissingReferenceContext() {
        EvaluationAutoScoringService service = new EvaluationAutoScoringService();
        EvaluationCaseRun caseRun = caseRun("THEME_RESEARCH", "[\"STORAGE\"]", "L2_THEME_RESEARCH", "储能需求增长");

        EvaluationAutoScoreRespDTO score = service.score(bundle("chunk-1"), caseRun, List.of(context("chunk-x", "parent-x", "其他证据")));

        Assertions.assertFalse(score.referenceContextHit());
        Assertions.assertTrue(score.needsManualReview());
    }

    @Test
    void shouldFlagForbiddenContextAndClaim() {
        EvaluationAutoScoringService service = new EvaluationAutoScoringService();
        EvaluationCaseRun caseRun = caseRun("THEME_RESEARCH", "[\"STORAGE\"]", "L2_THEME_RESEARCH", "给出目标价");

        EvaluationAutoScoreRespDTO score = service.score(bundle("chunk-1"), caseRun, List.of(context("chunk-1", "parent-1", "港口污染证据")));

        Assertions.assertTrue(score.referenceContextHit());
        Assertions.assertTrue(score.forbiddenContextHit());
        Assertions.assertTrue(score.forbiddenClaimHit());
        Assertions.assertTrue(score.needsManualReview());
    }

    private EvaluationCaseBundleDTO bundle(String referenceChunkUid) {
        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setExpectedIntent("THEME_RESEARCH");
        evaluationCase.setExpectedOutputLevel("L2_THEME_RESEARCH");
        evaluationCase.setForbiddenClaims("目标价");
        EvaluationCaseAnchor anchor = new EvaluationCaseAnchor();
        anchor.setAnchorCode("STORAGE");
        anchor.setRequired(Boolean.TRUE);
        EvaluationReferenceContext referenceContext = new EvaluationReferenceContext();
        referenceContext.setChunkUid(referenceChunkUid);
        EvaluationCaseForbiddenContext forbiddenContext = new EvaluationCaseForbiddenContext();
        forbiddenContext.setForbiddenType("KEYWORD");
        forbiddenContext.setForbiddenValue("港口");
        return new EvaluationCaseBundleDTO(evaluationCase, List.of(anchor), List.of(referenceContext), List.of(forbiddenContext));
    }

    private EvaluationCaseRun caseRun(String intent, String anchors, String outputLevel, String responseText) {
        EvaluationCaseRun caseRun = new EvaluationCaseRun();
        caseRun.setActualIntent(intent);
        caseRun.setActualAnchors(anchors);
        caseRun.setActualOutputLevel(outputLevel);
        caseRun.setResponseText(responseText);
        return caseRun;
    }

    private EvaluationRetrievedContext context(String chunkUid, String parentChunkUid, String text) {
        EvaluationRetrievedContext context = new EvaluationRetrievedContext();
        context.setStage("FINAL");
        context.setChunkUid(chunkUid);
        context.setParentChunkUid(parentChunkUid);
        context.setRetrievedText(text);
        return context;
    }
}
