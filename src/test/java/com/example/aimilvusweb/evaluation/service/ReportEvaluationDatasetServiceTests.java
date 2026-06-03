package com.example.aimilvusweb.evaluation.service;

import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportChunkTag;
import com.example.aimilvusweb.entity.ReportDocument;
import com.example.aimilvusweb.evaluation.entity.EvaluationCase;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseAnchor;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseForbiddenContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseReferenceContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationCorpus;
import com.example.aimilvusweb.evaluation.entity.EvaluationCorpusReport;
import com.example.aimilvusweb.evaluation.entity.EvaluationReferenceContext;
import com.example.aimilvusweb.evaluation.repository.EvaluationDatasetMapper;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 评测数据集服务测试，验证 corpus、报告快照、标准证据和 case 聚合写入。
 * @Logic: 使用 mock Mapper 捕获写入实体，确认业务字段被冗余到 eval 域并保持子表关联。
 * @Param: 详见测试方法。
 * @Return: 无。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
class ReportEvaluationDatasetServiceTests {

    @Test
    void shouldCreateCorpusWithDefaults() {
        EvaluationDatasetMapper mapper = mock(EvaluationDatasetMapper.class);
        ReportEvaluationDatasetService service = service(mapper, mock(ReportDocumentMapper.class), mock(ReportChunkMapper.class), mock(ReportChunkTagMapper.class));

        EvaluationCorpus corpus = service.createCorpus("core", "核心集", "", "", "", "text-embedding-v3");

        Assertions.assertEquals("core", corpus.getCorpusCode());
        Assertions.assertEquals("v1", corpus.getCorpusVersion());
        Assertions.assertEquals("v1", corpus.getDictionaryVersion());
        Assertions.assertEquals("ACTIVE", corpus.getStatus());
        verify(mapper).insertCorpus(corpus);
    }

    @Test
    void shouldSnapshotBusinessReportMetadata() {
        EvaluationDatasetMapper mapper = mock(EvaluationDatasetMapper.class);
        ReportDocumentMapper documentMapper = mock(ReportDocumentMapper.class);
        ReportDocument report = new ReportDocument();
        report.setId(10L);
        report.setTitle("储能深度");
        report.setSource("券商");
        report.setInstitution("机构");
        report.setPublishDate(LocalDate.of(2026, 6, 1));
        when(documentMapper.selectById(10L)).thenReturn(report);
        ReportEvaluationDatasetService service = service(mapper, documentMapper, mock(ReportChunkMapper.class), mock(ReportChunkTagMapper.class));

        EvaluationCorpusReport snapshot = service.addReportSnapshot(1L, 10L, "sha256");

        Assertions.assertEquals("储能深度", snapshot.getTitleSnapshot());
        Assertions.assertEquals("sha256", snapshot.getReportFingerprint());
        verify(mapper).insertCorpusReport(snapshot);
    }

    @Test
    void shouldCreateReferenceContextFromChunkAndTags() {
        EvaluationDatasetMapper mapper = mock(EvaluationDatasetMapper.class);
        ReportChunkMapper chunkMapper = mock(ReportChunkMapper.class);
        ReportChunkTagMapper tagMapper = mock(ReportChunkTagMapper.class);
        ReportChunk chunk = new ReportChunk();
        chunk.setReportId(10L);
        chunk.setChunkUid("chunk-1");
        chunk.setParentChunkUid("parent-1");
        chunk.setSectionPath("投资逻辑");
        chunk.setStartPageNumber(3);
        chunk.setEndPageNumber(4);
        chunk.setChunkText("储能需求增长");
        when(chunkMapper.selectByChunkUid("chunk-1")).thenReturn(chunk);
        when(tagMapper.selectByChunkUid("chunk-1")).thenReturn(List.of(tag("THEME", "STORAGE"), tag("INDUSTRY", "POWER_EQUIPMENT")));
        ReportEvaluationDatasetService service = service(mapper, mock(ReportDocumentMapper.class), chunkMapper, tagMapper);

        EvaluationReferenceContext context = service.createReferenceContextFromChunk(1L, "chunk-1", "");

        Assertions.assertEquals("STORAGE", context.getThemeCodes());
        Assertions.assertEquals("POWER_EQUIPMENT", context.getIndustryCodes());
        Assertions.assertEquals("储能需求增长", context.getReferenceText());
        verify(mapper).insertReferenceContext(context);
    }

    @Test
    void shouldCreateCaseWithChildren() {
        EvaluationDatasetMapper mapper = mock(EvaluationDatasetMapper.class);
        ReportEvaluationDatasetService service = service(mapper, mock(ReportDocumentMapper.class), mock(ReportChunkMapper.class), mock(ReportChunkTagMapper.class));
        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setCorpusId(1L);
        evaluationCase.setCaseId("case-1");
        evaluationCase.setQueryText("储能机会");
        evaluationCase.setCaseType("THEME_RESEARCH");
        EvaluationCaseAnchor anchor = new EvaluationCaseAnchor();
        anchor.setAnchorType("THEME");
        anchor.setAnchorCode("STORAGE");
        EvaluationCaseReferenceContext link = new EvaluationCaseReferenceContext();
        link.setReferenceContextId(2L);
        EvaluationCaseForbiddenContext forbiddenContext = new EvaluationCaseForbiddenContext();
        forbiddenContext.setForbiddenType("THEME");
        forbiddenContext.setForbiddenValue("PORT");

        service.createCase(evaluationCase, List.of(anchor), List.of(link), List.of(forbiddenContext));

        ArgumentCaptor<EvaluationCaseAnchor> anchorCaptor = ArgumentCaptor.forClass(EvaluationCaseAnchor.class);
        verify(mapper).insertCase(evaluationCase);
        verify(mapper).insertCaseAnchor(anchorCaptor.capture());
        verify(mapper).insertCaseReferenceContext(any(EvaluationCaseReferenceContext.class));
        verify(mapper).insertCaseForbiddenContext(any(EvaluationCaseForbiddenContext.class));
        Assertions.assertEquals(evaluationCase.getId(), anchorCaptor.getValue().getCasePkId());
    }

    private ReportEvaluationDatasetService service(EvaluationDatasetMapper mapper,
                                                   ReportDocumentMapper documentMapper,
                                                   ReportChunkMapper chunkMapper,
                                                   ReportChunkTagMapper tagMapper) {
        return new ReportEvaluationDatasetService(mapper, documentMapper, chunkMapper, tagMapper);
    }

    private ReportChunkTag tag(String tagType, String tagCode) {
        ReportChunkTag tag = new ReportChunkTag();
        tag.setTagType(tagType);
        tag.setTagCode(tagCode);
        return tag;
    }
}
