package com.example.aimilvusweb.service;

import com.example.aimilvusweb.entity.ReportChunkTag;
import com.example.aimilvusweb.entity.ReportDocumentTag;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ReportDocumentTagMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 报告级标签服务，负责维护 report_document_tag 父标签主数据。
 * @Logic: 默认从 chunk 标签聚合报告级父标签，并提供覆盖写入和诊断查询能力。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 写入后的报告级标签列表或诊断数量。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Service
public class ReportDocumentTagService {

    /** 报告级父标签来源：chunk 标签聚合。 */
    private static final String SOURCE_CHUNK_AGGREGATION = "CHUNK_AGGREGATION";
    /** 当前优先支持的报告级父标签类型。 */
    private static final String TAG_TYPE_THEME = "THEME";

    /** 报告级标签 Mapper。 */
    private final ReportDocumentTagMapper reportDocumentTagMapper;
    /** chunk 标签 Mapper，用于聚合报告级父标签。 */
    private final ReportChunkTagMapper reportChunkTagMapper;

    /**
     * @Description: 初始化报告级标签服务依赖。
     * @Logic: 保存 report 与 chunk 标签 Mapper，供聚合和覆盖写入复用。
     * @Param: reportDocumentTagMapper 报告级标签 Mapper；reportChunkTagMapper chunk 标签 Mapper。
     * @Return: 无（仅初始化对象状态）。
     */
    public ReportDocumentTagService(ReportDocumentTagMapper reportDocumentTagMapper,
                                    ReportChunkTagMapper reportChunkTagMapper) {
        this.reportDocumentTagMapper = reportDocumentTagMapper;
        this.reportChunkTagMapper = reportChunkTagMapper;
    }

    /**
     * @Description: 从 chunk 标签聚合并覆盖写入报告级父标签。
     * @Logic: 第一版聚合 THEME 标签；按命中次数、置信度和编码稳定排序后写入 report_document_tag。
     * @Param: reportId 研报 ID；dictionaryVersion 词库版本。
     * @Return: 写入的报告级标签列表。
     */
    @Transactional
    public List<ReportDocumentTag> refreshFromChunkTags(Long reportId, String dictionaryVersion) {
        List<ReportDocumentTag> documentTags = aggregateThemeTags(reportId, dictionaryVersion);
        replaceReportTags(reportId, dictionaryVersion, documentTags);
        return documentTags;
    }

    /**
     * @Description: 覆盖写入指定报告和版本的报告级标签。
     * @Logic: 先删除同报告同版本旧标签，再按去重后的标签列表插入最新结果。
     * @Param: reportId 研报 ID；dictionaryVersion 词库版本；tags 待写入标签。
     * @Return: 写入后的标签列表。
     */
    @Transactional
    public List<ReportDocumentTag> replaceReportTags(Long reportId, String dictionaryVersion, List<ReportDocumentTag> tags) {
        reportDocumentTagMapper.deleteByReportIdAndVersion(reportId, dictionaryVersion);
        List<ReportDocumentTag> normalizedTags = deduplicate(tags);
        for (ReportDocumentTag tag : normalizedTags) {
            reportDocumentTagMapper.insert(tag);
        }
        return normalizedTags;
    }

    /**
     * @Description: 查询指定报告的报告级标签。
     * @Logic: 直接读取 report_document_tag 主数据，供 metadata 同步使用。
     * @Param: reportId 研报 ID。
     * @Return: 标签列表。
     */
    public List<ReportDocumentTag> selectByReportId(Long reportId) {
        return reportDocumentTagMapper.selectByReportId(reportId);
    }

    /**
     * @Description: 诊断报告级父标签是否存在。
     * @Logic: 用于 Milvus metadata filter 无结果时区分主数据缺失和同步滞后。
     * @Param: tagType 标签类型；tagCodes 标签编码。
     * @Return: 命中数量。
     */
    public int countByTagCodes(String tagType, List<String> tagCodes) {
        if (tagCodes == null || tagCodes.isEmpty()) {
            return 0;
        }
        return reportDocumentTagMapper.countByTagCodes(tagType, tagCodes);
    }

    /**
     * @Description: 从 chunk 标签聚合主题父标签。
     * @Logic: 相同主题按命中次数累计，置信度保留最高值，最终作为报告级父标签写入。
     * @Param: reportId 研报 ID；dictionaryVersion 词库版本。
     * @Return: 聚合后的报告级主题标签。
     */
    private List<ReportDocumentTag> aggregateThemeTags(Long reportId, String dictionaryVersion) {
        Map<String, AggregatedTag> aggregatedTags = new LinkedHashMap<>();
        for (ReportChunkTag chunkTag : reportChunkTagMapper.selectByReportId(reportId)) {
            if (!TAG_TYPE_THEME.equals(chunkTag.getTagType()) || !dictionaryVersion.equals(chunkTag.getDictionaryVersion())) {
                continue;
            }
            String key = chunkTag.getTagType() + "|" + chunkTag.getTagCode();
            aggregatedTags.computeIfAbsent(key, ignored -> new AggregatedTag(chunkTag)).add(chunkTag);
        }
        Instant now = Instant.now();
        return aggregatedTags.values().stream()
                .sorted(Comparator.comparing(AggregatedTag::count).reversed()
                        .thenComparing(AggregatedTag::confidence, Comparator.reverseOrder())
                        .thenComparing(AggregatedTag::tagCode))
                .map(value -> value.toDocumentTag(reportId, dictionaryVersion, now))
                .toList();
    }

    /**
     * @Description: 去重报告级标签。
     * @Logic: 相同 tagType/tagCode 仅保留首个标签，避免唯一键冲突。
     * @Param: tags 原始标签列表。
     * @Return: 去重后的标签列表。
     */
    private List<ReportDocumentTag> deduplicate(List<ReportDocumentTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        Map<String, ReportDocumentTag> deduplicated = new LinkedHashMap<>();
        for (ReportDocumentTag tag : tags) {
            String key = tag.getTagType() + "|" + tag.getTagCode();
            deduplicated.putIfAbsent(key, tag);
        }
        return List.copyOf(deduplicated.values());
    }

    /**
     * @Description: 聚合中的报告级标签候选。
     * @Logic: 记录最高置信度和命中次数，用于稳定选出父标签顺序。
     * @Param: tagType/tagCode/tagName 标签信息；confidence 最高置信度；count 命中次数。
     */
    private static final class AggregatedTag {

        /** 标签类型。 */
        private final String tagType;
        /** 标签编码。 */
        private final String tagCode;
        /** 标签名称。 */
        private final String tagName;
        /** 聚合最高置信度。 */
        private BigDecimal confidence;
        /** 命中次数。 */
        private int count;

        private AggregatedTag(ReportChunkTag tag) {
            this.tagType = tag.getTagType();
            this.tagCode = tag.getTagCode();
            this.tagName = tag.getTagName();
            this.confidence = safeConfidence(tag.getConfidence());
        }

        private void add(ReportChunkTag tag) {
            count++;
            BigDecimal candidateConfidence = safeConfidence(tag.getConfidence());
            if (candidateConfidence.compareTo(confidence) > 0) {
                confidence = candidateConfidence;
            }
        }

        private int count() {
            return count;
        }

        private BigDecimal confidence() {
            return confidence;
        }

        private String tagCode() {
            return tagCode;
        }

        private ReportDocumentTag toDocumentTag(Long reportId, String dictionaryVersion, Instant now) {
            ReportDocumentTag tag = new ReportDocumentTag();
            tag.setReportId(reportId);
            tag.setTagType(tagType);
            tag.setTagCode(tagCode);
            tag.setTagName(tagName);
            tag.setConfidence(confidence);
            tag.setSource(SOURCE_CHUNK_AGGREGATION);
            tag.setDictionaryVersion(dictionaryVersion);
            tag.setCreatedAt(now);
            tag.setUpdatedAt(now);
            return tag;
        }

        private static BigDecimal safeConfidence(BigDecimal confidence) {
            return confidence == null ? BigDecimal.ZERO : confidence;
        }
    }
}
