package com.example.aimilvusweb.service.tag;

import com.example.aimilvusweb.entity.ReportChunkTag;
import com.example.aimilvusweb.entity.ReportDocumentTag;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ReportDocumentTagMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportDocumentTagService 测试，验证报告级父标签聚合和覆盖写入行为。
 * @Logic: 从 chunk 主题标签聚合出 report_document_tag，并确认重算前清理旧版本标签。
 * @Param: 详见测试方法。
 * @Return: 无。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
class ReportDocumentTagServiceTests {

    @Test
    void shouldAggregateThemeTagsFromChunkTags() {
        ReportDocumentTagMapper documentTagMapper = mock(ReportDocumentTagMapper.class);
        ReportChunkTagMapper chunkTagMapper = mock(ReportChunkTagMapper.class);
        ReportDocumentTagService service = new ReportDocumentTagService(documentTagMapper, chunkTagMapper);
        when(chunkTagMapper.selectByReportId(10L)).thenReturn(List.of(
                chunkTag("THEME", "STORAGE", "储能", "v1", BigDecimal.valueOf(0.90D)),
                chunkTag("THEME", "STORAGE", "储能", "v1", BigDecimal.valueOf(0.95D)),
                chunkTag("INDUSTRY", "POWER_EQUIPMENT", "电力设备", "v1", BigDecimal.ONE)
        ));

        List<ReportDocumentTag> tags = service.refreshFromChunkTags(10L, "v1");

        Assertions.assertEquals(1, tags.size());
        Assertions.assertEquals("THEME", tags.get(0).getTagType());
        Assertions.assertEquals("STORAGE", tags.get(0).getTagCode());
        Assertions.assertEquals(BigDecimal.valueOf(0.95D), tags.get(0).getConfidence());
        verify(documentTagMapper).deleteByReportIdVersionAndSource(10L, "v1", "CHUNK_AGGREGATION");
        verify(documentTagMapper).insert(any(ReportDocumentTag.class));
    }

    @Test
    void shouldDeduplicateExplicitReportTagsBeforeInsert() {
        ReportDocumentTagMapper documentTagMapper = mock(ReportDocumentTagMapper.class);
        ReportChunkTagMapper chunkTagMapper = mock(ReportChunkTagMapper.class);
        ReportDocumentTagService service = new ReportDocumentTagService(documentTagMapper, chunkTagMapper);
        ReportDocumentTag first = documentTag("THEME", "STORAGE");
        ReportDocumentTag duplicate = documentTag("THEME", "STORAGE");

        List<ReportDocumentTag> tags = service.replaceReportTags(10L, "v1", List.of(first, duplicate));

        Assertions.assertEquals(1, tags.size());
        verify(documentTagMapper).deleteByReportIdAndVersion(10L, "v1");
        verify(documentTagMapper, times(1)).insert(any(ReportDocumentTag.class));
    }

    @Test
    void shouldClearOldTagsWhenAggregationIsEmpty() {
        ReportDocumentTagMapper documentTagMapper = mock(ReportDocumentTagMapper.class);
        ReportChunkTagMapper chunkTagMapper = mock(ReportChunkTagMapper.class);
        ReportDocumentTagService service = new ReportDocumentTagService(documentTagMapper, chunkTagMapper);
        when(chunkTagMapper.selectByReportId(10L)).thenReturn(List.of());

        List<ReportDocumentTag> tags = service.refreshFromChunkTags(10L, "v1");

        Assertions.assertTrue(tags.isEmpty());
        verify(documentTagMapper).deleteByReportIdVersionAndSource(10L, "v1", "CHUNK_AGGREGATION");
        verify(documentTagMapper, times(0)).insert(any(ReportDocumentTag.class));
    }

    @Test
    void shouldPersistImportMetadataTagsBySource() {
        ReportDocumentTagMapper documentTagMapper = mock(ReportDocumentTagMapper.class);
        ReportChunkTagMapper chunkTagMapper = mock(ReportChunkTagMapper.class);
        ReportDocumentTagService service = new ReportDocumentTagService(documentTagMapper, chunkTagMapper);

        List<ReportDocumentTag> tags = service.refreshFromImportMetadata(
                10L,
                "v1",
                "STORAGE:储能",
                "POWER_EQUIPMENT:电力设备",
                "宁德时代",
                "300750.SZ"
        );

        Assertions.assertEquals(4, tags.size());
        Assertions.assertEquals("STORAGE", tags.get(0).getTagCode());
        Assertions.assertEquals("储能", tags.get(0).getTagName());
        Assertions.assertEquals("宁德时代", tags.get(2).getTagCode());
        verify(documentTagMapper).deleteByReportIdVersionAndSource(10L, "v1", "IMPORT_METADATA");
        verify(documentTagMapper, times(4)).insert(any(ReportDocumentTag.class));
    }

    private ReportChunkTag chunkTag(String tagType, String tagCode, String tagName, String version, BigDecimal confidence) {
        ReportChunkTag tag = new ReportChunkTag();
        tag.setReportId(10L);
        tag.setChunkUid("chunk-" + tagCode);
        tag.setTagType(tagType);
        tag.setTagCode(tagCode);
        tag.setTagName(tagName);
        tag.setConfidence(confidence);
        tag.setDictionaryVersion(version);
        return tag;
    }

    private ReportDocumentTag documentTag(String tagType, String tagCode) {
        ReportDocumentTag tag = new ReportDocumentTag();
        tag.setReportId(10L);
        tag.setTagType(tagType);
        tag.setTagCode(tagCode);
        tag.setTagName("储能");
        tag.setConfidence(BigDecimal.ONE);
        tag.setDictionaryVersion("v1");
        return tag;
    }
}
