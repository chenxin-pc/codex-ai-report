package com.example.aimilvusweb.service.tag;

import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportChunkTag;
import com.example.aimilvusweb.entity.ResearchDictionaryTerm;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ResearchTaxonomyMapper;
import com.example.aimilvusweb.service.taxonomy.ResearchTaxonomySnapshotService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportChunkTagExtractionService 测试，验证 chunk 标签抽取和落库行为。
 * @Logic: 构造储能 chunk，确认抽取结果写入 THEME=STORAGE 且会清理旧版本标签。
 * @Param: 详见测试方法。
 * @Return: 无。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
class ReportChunkTagExtractionServiceTests {

    @Test
    void shouldExtractAndPersistChunkTags() {
        ResearchTaxonomyMapper taxonomyMapper = mock(ResearchTaxonomyMapper.class);
        ReportChunkTagMapper tagMapper = mock(ReportChunkTagMapper.class);
        when(taxonomyMapper.selectActiveDictionaryVersion()).thenReturn("v1");
        when(taxonomyMapper.selectActiveDictionaryTerms()).thenReturn(List.of(term("THEME", "STORAGE", "储能", "储能")));
        ReportChunkTagExtractionService service = new ReportChunkTagExtractionService(new ResearchTaxonomySnapshotService(taxonomyMapper), tagMapper);
        ReportChunk chunk = new ReportChunk();
        chunk.setReportId(10L);
        chunk.setChunkUid("chunk-1");
        chunk.setSectionPath("投资要点");
        chunk.setChunkText("公司积极布局储能系统，需求持续增长。");

        List<ReportChunkTag> tags = service.extractAndPersist(chunk, "v1");

        Assertions.assertEquals(1, tags.size());
        Assertions.assertEquals("STORAGE", tags.get(0).getTagCode());
        verify(tagMapper).deleteByChunkUidAndVersion("chunk-1", "v1");
        verify(tagMapper).insert(any(ReportChunkTag.class));
    }

    private ResearchDictionaryTerm term(String tagType, String tagCode, String tagName, String termText) {
        ResearchDictionaryTerm term = new ResearchDictionaryTerm();
        term.setTagType(tagType);
        term.setTagCode(tagCode);
        term.setTagName(tagName);
        term.setTermText(termText);
        term.setNormalizedTerm(termText);
        term.setRelationType("REQUIRED");
        term.setWeight(BigDecimal.ONE);
        term.setDictionaryVersion("v1");
        return term;
    }
}
