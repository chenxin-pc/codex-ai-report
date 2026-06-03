package com.example.aimilvusweb.evaluation.repository;

import com.example.aimilvusweb.evaluation.entity.EvaluationCase;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseReferenceContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationCorpus;
import com.example.aimilvusweb.evaluation.entity.EvaluationReferenceContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationRetrievedContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationRun;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseRun;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * @Description: 评测 Mapper 集成测试，验证 eval 表结构和 MyBatis XML 可在测试数据库中完成核心读写。
 * @Logic: 使用 H2 MySQL 模式启动 Spring 上下文，依次写入 corpus、case、reference context、run 和 retrieved context。
 * @Param: 详见测试方法。
 * @Return: 无。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@SpringBootTest
@ActiveProfiles("test")
class EvaluationMapperIntegrationTests {

    @Autowired
    private EvaluationDatasetMapper datasetMapper;

    @Autowired
    private EvaluationRunMapper runMapper;

    @Test
    void shouldInsertAndReadEvaluationDatasetAndRunRecords() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        EvaluationCorpus corpus = new EvaluationCorpus();
        corpus.setCorpusCode("core-" + suffix);
        corpus.setName("核心评测集");
        corpus.setCorpusVersion("v1");
        corpus.setDictionaryVersion("v1");
        corpus.setStatus("ACTIVE");
        corpus.setCreatedAt(now);
        corpus.setUpdatedAt(now);
        datasetMapper.insertCorpus(corpus);

        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setCorpusId(corpus.getId());
        evaluationCase.setCaseId("case-" + suffix);
        evaluationCase.setQueryText("储能机会");
        evaluationCase.setCaseType("THEME_RESEARCH");
        evaluationCase.setEnabled(Boolean.TRUE);
        evaluationCase.setCreatedAt(now);
        evaluationCase.setUpdatedAt(now);
        datasetMapper.insertCase(evaluationCase);

        EvaluationReferenceContext referenceContext = new EvaluationReferenceContext();
        referenceContext.setCorpusId(corpus.getId());
        referenceContext.setChunkUid("chunk-" + suffix);
        referenceContext.setContextType("CHILD");
        referenceContext.setReferenceText("储能需求增长");
        referenceContext.setCreatedAt(now);
        datasetMapper.insertReferenceContext(referenceContext);

        EvaluationCaseReferenceContext link = new EvaluationCaseReferenceContext();
        link.setCasePkId(evaluationCase.getId());
        link.setReferenceContextId(referenceContext.getId());
        link.setRelevanceLevel(3);
        link.setRequired(Boolean.TRUE);
        link.setCreatedAt(now);
        datasetMapper.insertCaseReferenceContext(link);

        EvaluationRun run = new EvaluationRun();
        run.setRunId("run-" + suffix);
        run.setCorpusId(corpus.getId());
        run.setStatus("RUNNING");
        run.setStartedAt(now);
        run.setCreatedAt(now);
        runMapper.insertRun(run);

        EvaluationCaseRun caseRun = new EvaluationCaseRun();
        caseRun.setEvalRunId(run.getId());
        caseRun.setCasePkId(evaluationCase.getId());
        caseRun.setQueryText("储能机会");
        caseRun.setStatus("RUNNING");
        caseRun.setStartedAt(now);
        caseRun.setCreatedAt(now);
        runMapper.insertCaseRun(caseRun);

        EvaluationRetrievedContext retrievedContext = new EvaluationRetrievedContext();
        retrievedContext.setCaseRunId(caseRun.getId());
        retrievedContext.setStage("FINAL");
        retrievedContext.setRankNo(1);
        retrievedContext.setChunkUid("chunk-" + suffix);
        retrievedContext.setScore(BigDecimal.valueOf(0.9D));
        retrievedContext.setRetrievedText("储能需求增长");
        retrievedContext.setDiagnosticOnly(Boolean.FALSE);
        retrievedContext.setTruncated(Boolean.FALSE);
        retrievedContext.setCreatedAt(now);
        runMapper.insertRetrievedContext(retrievedContext);

        Assertions.assertNotNull(datasetMapper.selectCorpusById(corpus.getId()));
        Assertions.assertEquals(1, datasetMapper.selectReferenceContextsByCasePkId(evaluationCase.getId()).size());
        Assertions.assertNotNull(runMapper.selectRunByRunId(run.getRunId()));
        Assertions.assertEquals(1, runMapper.selectRetrievedContextsByCaseRunId(caseRun.getId()).size());
    }
}
