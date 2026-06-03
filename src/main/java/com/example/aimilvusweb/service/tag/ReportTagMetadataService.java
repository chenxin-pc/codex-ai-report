package com.example.aimilvusweb.service.tag;

import com.example.aimilvusweb.entity.ReportChunkTag;
import com.example.aimilvusweb.entity.ReportDocumentTag;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ReportDocumentTagMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 研报标签 metadata 服务，负责把 MySQL 标签主数据整理为 Milvus metadata 摘要和快照哈希。
 * @Logic: 按 tagType 聚合标签编码与名称，生成可过滤字段、可解释字段和幂等同步用 hash。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 标签 metadata 摘要或快照哈希。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Service
public class ReportTagMetadataService {

    /** 主题标签类型。 */
    private static final String TAG_TYPE_THEME = "THEME";
    /** 行业标签类型。 */
    private static final String TAG_TYPE_INDUSTRY = "INDUSTRY";
    /** 公司标签类型。 */
    private static final String TAG_TYPE_COMPANY = "COMPANY";
    /** 股票代码标签类型。 */
    private static final String TAG_TYPE_TICKER = "TICKER";

    /** chunk 标签主数据 Mapper。 */
    private final ReportChunkTagMapper reportChunkTagMapper;
    /** 报告级标签主数据 Mapper。 */
    private final ReportDocumentTagMapper reportDocumentTagMapper;

    /**
     * @Description: 初始化标签 metadata 服务依赖。
     * @Logic: 保存标签 Mapper，用于按 chunkUid 查询标签主数据。
     * @Param: reportChunkTagMapper chunk 标签 Mapper。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    @Autowired
    public ReportTagMetadataService(ReportChunkTagMapper reportChunkTagMapper,
                                    ReportDocumentTagMapper reportDocumentTagMapper) {
        this.reportChunkTagMapper = reportChunkTagMapper;
        this.reportDocumentTagMapper = reportDocumentTagMapper;
    }

    /**
     * @Description: 兼容旧测试的标签 metadata 服务构造器。
     * @Logic: 未提供报告级标签 Mapper 时仅使用 chunk 标签构建 metadata。
     * @Param: reportChunkTagMapper chunk 标签 Mapper。
     * @Return: 无（仅初始化对象状态）。
     */
    public ReportTagMetadataService(ReportChunkTagMapper reportChunkTagMapper) {
        this(reportChunkTagMapper, null);
    }

    /**
     * @Description: 按 chunkUid 读取标签并构建 metadata 摘要。
     * @Logic: 查询 MySQL 标签主数据后按类型聚合，生成 Milvus 可存储的标量摘要字段。
     * @Param: chunkUid 切片唯一标识。
     * @Return: 标签 metadata 摘要。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public TagMetadata metadataForChunk(String chunkUid) {
        return toMetadata(reportChunkTagMapper.selectByChunkUid(chunkUid));
    }

    /**
     * @Description: 按 reportId 和 chunkUid 读取标签并构建 metadata 摘要。
     * @Logic: 同时聚合报告级父标签和 chunk 级证据标签，供 Milvus metadata 同步使用。
     * @Param: reportId 研报 ID；chunkUid 切片唯一标识。
     * @Return: 标签 metadata 摘要。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public TagMetadata metadataForReportAndChunk(Long reportId, String chunkUid) {
        List<ReportDocumentTag> documentTags = reportDocumentTagMapper == null ? List.of() : reportDocumentTagMapper.selectByReportId(reportId);
        return toMetadata(documentTags, reportChunkTagMapper.selectByChunkUid(chunkUid));
    }

    /**
     * @Description: 把标签列表转换为 metadata 摘要。
     * @Logic: 按标签类型分桶，列表字段用于解释，primary 字段用于 Milvus scalar filter。
     * @Param: tags 标签列表。
     * @Return: 标签 metadata 摘要。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public TagMetadata toMetadata(List<ReportChunkTag> tags) {
        return toMetadata(List.of(), tags);
    }

    /**
     * @Description: 把报告级标签和 chunk 标签转换为 metadata 摘要。
     * @Logic: 报告级标签用于报告集合过滤，chunk 标签用于证据过滤和展示。
     * @Param: documentTags 报告级标签列表；chunkTags chunk 级标签列表。
     * @Return: 标签 metadata 摘要。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public TagMetadata toMetadata(List<ReportDocumentTag> documentTags, List<ReportChunkTag> chunkTags) {
        // 标签为空时返回空摘要，调用方仍会写入空字符串标量字段。
        if ((documentTags == null || documentTags.isEmpty()) && (chunkTags == null || chunkTags.isEmpty())) {
            return TagMetadata.empty();
        }
        // 按 tagType 聚合并按置信度排序，确保 primary 字段稳定。
        Map<String, List<ReportChunkTag>> groupedChunkTags = new LinkedHashMap<>();
        if (chunkTags != null) {
            for (ReportChunkTag tag : chunkTags) {
                groupedChunkTags.computeIfAbsent(tag.getTagType(), ignored -> new ArrayList<>()).add(tag);
            }
        }
        // 每个类型按置信度和编码排序，保证 metadata 和 hash 可复现。
        groupedChunkTags.values().forEach(values -> values.sort(Comparator
                .comparing((ReportChunkTag tag) -> tag.getConfidence() == null ? java.math.BigDecimal.ZERO : tag.getConfidence()).reversed()
                .thenComparing(ReportChunkTag::getTagCode)));
        Map<String, List<ReportDocumentTag>> groupedDocumentTags = new LinkedHashMap<>();
        if (documentTags != null) {
            for (ReportDocumentTag tag : documentTags) {
                groupedDocumentTags.computeIfAbsent(tag.getTagType(), ignored -> new ArrayList<>()).add(tag);
            }
        }
        groupedDocumentTags.values().forEach(values -> values.sort(Comparator
                .comparing((ReportDocumentTag tag) -> tag.getConfidence() == null ? java.math.BigDecimal.ZERO : tag.getConfidence()).reversed()
                .thenComparing(ReportDocumentTag::getTagCode)));
        return new TagMetadata(
                documentCodes(groupedDocumentTags.get(TAG_TYPE_THEME)),
                codes(groupedChunkTags.get(TAG_TYPE_THEME)),
                codes(groupedChunkTags.get(TAG_TYPE_INDUSTRY)),
                names(groupedChunkTags.get(TAG_TYPE_COMPANY)),
                codes(groupedChunkTags.get(TAG_TYPE_COMPANY)),
                codes(groupedChunkTags.get(TAG_TYPE_TICKER))
        );
    }

    /**
     * @Description: 计算标签快照哈希。
     * @Logic: 将标签 metadata 的核心字段稳定串联后计算 SHA-256，用于 metadata sync job 幂等判断。
     * @Param: metadata 标签 metadata 摘要。
     * @Return: SHA-256 十六进制哈希。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public String snapshotHash(TagMetadata metadata) {
        // 将所有标签字段按固定顺序拼接，避免 Map 顺序影响 hash。
        String raw = String.join("|",
                String.join(",", metadata.reportThemeCodes()),
                String.join(",", metadata.themeCodes()),
                String.join(",", metadata.industryCodes()),
                String.join(",", metadata.companyNames()),
                String.join(",", metadata.companyCodes()),
                String.join(",", metadata.tickers()));
        try {
            // SHA-256 足够用于同步幂等和快照变更识别。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必定提供 SHA-256；异常时回退 hashCode，避免同步链路完全中断。
            return Integer.toHexString(raw.hashCode());
        }
    }

    /**
     * @Description: 提取标签编码列表。
     * @Logic: 空列表返回空集合；非空时保持排序后的 tagCode 顺序并去重。
     * @Param: tags 标签列表。
     * @Return: 标签编码列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private List<String> codes(List<ReportChunkTag> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream().map(ReportChunkTag::getTagCode).filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    /**
     * @Description: 提取标签名称列表。
     * @Logic: 空列表返回空集合；非空时保持排序后的 tagName 顺序并去重。
     * @Param: tags 标签列表。
     * @Return: 标签名称列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private List<String> names(List<ReportChunkTag> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream().map(ReportChunkTag::getTagName).filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    /**
     * @Description: 提取报告级标签编码列表。
     * @Logic: 空列表返回空集合；非空时保持排序后的 tagCode 顺序并去重。
     * @Param: tags 标签列表。
     * @Return: 标签编码列表。
     */
    private List<String> documentCodes(List<ReportDocumentTag> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream().map(ReportDocumentTag::getTagCode).filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    /**
     * @Description: 标签 metadata 摘要值对象。
     * @Logic: 列表字段用于解释和展示，primary 方法用于 Milvus scalar filter 的稳定单值字段。
     * @Param: themeCodes 主题编码；industryCodes 行业编码；companyNames 公司名称；companyCodes 公司编码；tickers 股票代码。
     */
    public record TagMetadata(
            /** 报告级主题父标签编码列表。 */
            List<String> reportThemeCodes,
            /** 主题标签编码列表。 */
            List<String> themeCodes,
            /** 行业标签编码列表。 */
            List<String> industryCodes,
            /** 公司名称列表。 */
            List<String> companyNames,
            /** 公司编码列表，当前通常与 ticker 保持一致。 */
            List<String> companyCodes,
            /** 股票代码列表。 */
            List<String> tickers
    ) {
        /**
         * @Description: 构造空 metadata 摘要。
         * @Logic: 标签未命中时返回空集合，调用方不需要判空。
         * @Return: 空 metadata 摘要。
         */
        private static TagMetadata empty() {
            return new TagMetadata(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        /**
         * @Description: 获取主报告级主题编码。
         * @Logic: 取报告级主题列表第一个值作为 Milvus scalar filter 的单值字段。
         * @Return: 主报告级主题编码；无主题时为空字符串。
         */
        public String primaryReportThemeCode() {
            return reportThemeCodes.isEmpty() ? "" : reportThemeCodes.get(0);
        }

        /**
         * @Description: 获取主主题编码。
         * @Logic: 取主题列表第一个值作为 Milvus scalar filter 的单值字段。
         * @Return: 主主题编码；无主题时为空字符串。
         */
        public String primaryThemeCode() {
            return themeCodes.isEmpty() ? "" : themeCodes.get(0);
        }

        /**
         * @Description: 获取主行业编码。
         * @Logic: 取行业列表第一个值作为 Milvus scalar filter 的单值字段。
         * @Return: 主行业编码；无行业时为空字符串。
         */
        public String primaryIndustryCode() {
            return industryCodes.isEmpty() ? "" : industryCodes.get(0);
        }

        /**
         * @Description: 获取主公司名称。
         * @Logic: 取公司名称列表第一个值作为 Milvus scalar filter 的单值字段。
         * @Return: 主公司名称；无公司时为空字符串。
         */
        public String primaryCompanyName() {
            return companyNames.isEmpty() ? "" : companyNames.get(0);
        }

        /**
         * @Description: 获取主股票代码。
         * @Logic: 取股票代码列表第一个值作为 Milvus scalar filter 的单值字段。
         * @Return: 主股票代码；无代码时为空字符串。
         */
        public String primaryTicker() {
            return tickers.isEmpty() ? "" : tickers.get(0);
        }
    }
}
