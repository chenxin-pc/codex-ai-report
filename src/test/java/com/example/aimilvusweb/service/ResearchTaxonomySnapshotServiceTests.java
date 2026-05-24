package com.example.aimilvusweb.service;

import com.example.aimilvusweb.entity.ResearchDictionaryTerm;
import com.example.aimilvusweb.repository.ResearchTaxonomyMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: ResearchTaxonomySnapshotService 测试，验证 ACTIVE 词库快照和 Trie 匹配能力。
 * @Logic: 使用 mock Mapper 构造储能词条，确保匹配结果包含结构化主题标签而不是逐词 contains 输出。
 * @Param: 详见测试方法。
 * @Return: 无。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
class ResearchTaxonomySnapshotServiceTests {

    @Test
    void shouldMatchThemeTermsFromSnapshot() {
        ResearchTaxonomyMapper mapper = mock(ResearchTaxonomyMapper.class);
        ResearchTaxonomySnapshotService service = new ResearchTaxonomySnapshotService(mapper);
        when(mapper.selectActiveDictionaryVersion()).thenReturn("v1");
        when(mapper.selectActiveDictionaryTerms()).thenReturn(List.of(term("THEME", "STORAGE", "储能", "新型储能")));

        List<ResearchTaxonomySnapshotService.TaxonomyMatch> matches = service.match("哪些研报看好新型储能板块");

        Assertions.assertEquals(1, matches.size());
        Assertions.assertEquals("THEME", matches.get(0).entry().tagType());
        Assertions.assertEquals("STORAGE", matches.get(0).entry().tagCode());
    }

    private ResearchDictionaryTerm term(String tagType, String tagCode, String tagName, String termText) {
        ResearchDictionaryTerm term = new ResearchDictionaryTerm();
        term.setTagType(tagType);
        term.setTagCode(tagCode);
        term.setTagName(tagName);
        term.setTermText(termText);
        term.setNormalizedTerm(termText);
        term.setRelationType("ALIAS");
        term.setWeight(BigDecimal.ONE);
        term.setDictionaryVersion("v1");
        return term;
    }
}
