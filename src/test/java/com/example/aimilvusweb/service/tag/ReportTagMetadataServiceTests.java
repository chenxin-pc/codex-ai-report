package com.example.aimilvusweb.service.tag;

import com.example.aimilvusweb.entity.ReportChunkTag;
import com.example.aimilvusweb.entity.ReportDocumentTag;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ReportDocumentTagMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportTagMetadataService 测试，验证报告级父标签和 chunk 标签 metadata 摘要。
 * @Logic: 构造 report_document_tag 与 report_chunk_tag，确认 primary 字段、列表字段和快照哈希会包含父标签。
 * @Param: 详见测试方法。
 * @Return: 无。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
class ReportTagMetadataServiceTests {

    @Test
    void shouldBuildMetadataWithReportAndChunkTags() {
        ReportChunkTagMapper chunkTagMapper = mock(ReportChunkTagMapper.class);
        ReportDocumentTagMapper documentTagMapper = mock(ReportDocumentTagMapper.class);
        ReportTagMetadataService service = new ReportTagMetadataService(chunkTagMapper, documentTagMapper);
        when(documentTagMapper.selectByReportId(10L)).thenReturn(List.of(documentTag("STORAGE")));
        when(chunkTagMapper.selectByChunkUid("chunk-1")).thenReturn(List.of(
                chunkTag("THEME", "STORAGE"),
                chunkTag("INDUSTRY", "POWER_EQUIPMENT")
        ));

        ReportTagMetadataService.TagMetadata metadata = service.metadataForReportAndChunk(10L, "chunk-1");

        Assertions.assertEquals("STORAGE", metadata.primaryReportThemeCode());
        Assertions.assertEquals(List.of("STORAGE"), metadata.reportThemeCodes());
        Assertions.assertEquals("STORAGE", metadata.primaryThemeCode());
        Assertions.assertEquals("POWER_EQUIPMENT", metadata.primaryIndustryCode());
    }

    @Test
    void shouldIncludeReportTagsInSnapshotHash() {
        ReportTagMetadataService service = new ReportTagMetadataService(mock(ReportChunkTagMapper.class));
        ReportTagMetadataService.TagMetadata withoutReportTag = new ReportTagMetadataService.TagMetadata(
                List.of(), List.of("STORAGE"), List.of(), List.of(), List.of(), List.of()
        );
        ReportTagMetadataService.TagMetadata withReportTag = new ReportTagMetadataService.TagMetadata(
                List.of("STORAGE"), List.of("STORAGE"), List.of(), List.of(), List.of(), List.of()
        );

        Assertions.assertNotEquals(service.snapshotHash(withoutReportTag), service.snapshotHash(withReportTag));
    }

    private ReportDocumentTag documentTag(String tagCode) {
        ReportDocumentTag tag = new ReportDocumentTag();
        tag.setTagType("THEME");
        tag.setTagCode(tagCode);
        tag.setTagName("储能");
        tag.setConfidence(BigDecimal.ONE);
        return tag;
    }

    private ReportChunkTag chunkTag(String tagType, String tagCode) {
        ReportChunkTag tag = new ReportChunkTag();
        tag.setTagType(tagType);
        tag.setTagCode(tagCode);
        tag.setTagName(tagCode);
        tag.setConfidence(BigDecimal.ONE);
        return tag;
    }
}
