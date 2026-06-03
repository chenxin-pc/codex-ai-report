package com.example.aimilvusweb.service.tag;

import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportChunkTag;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.service.taxonomy.ResearchTaxonomySnapshotService;
import com.example.aimilvusweb.service.taxonomy.ResearchTaxonomySnapshotService.TaxonomyMatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 子切片结构化标签抽取服务，基于 ACTIVE 词库为 chunk 生成主题、行业、公司和代码标签。
 * @Logic: 组合 sectionPath 与 chunkText 做词库匹配，按标签去重后覆盖写入 report_chunk_tag。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 本次抽取写入的标签列表。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Service
public class ReportChunkTagExtractionService {

    /** 标签来源固定为词库匹配。 */
    private static final String TAG_SOURCE_DICTIONARY = "DICTIONARY";

    /** 词库快照服务，用于匹配 chunk 文本中的结构化标签。 */
    private final ResearchTaxonomySnapshotService taxonomySnapshotService;
    /** chunk 标签 Mapper，用于覆盖写入标签主数据。 */
    private final ReportChunkTagMapper reportChunkTagMapper;

    /**
     * @Description: 初始化 chunk 标签抽取服务依赖。
     * @Logic: 保存词库快照服务和标签 Mapper，供 job 执行时复用。
     * @Param: taxonomySnapshotService 词库快照服务；reportChunkTagMapper 标签 Mapper。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public ReportChunkTagExtractionService(ResearchTaxonomySnapshotService taxonomySnapshotService,
                                           ReportChunkTagMapper reportChunkTagMapper) {
        this.taxonomySnapshotService = taxonomySnapshotService;
        this.reportChunkTagMapper = reportChunkTagMapper;
    }

    /**
     * @Description: 为指定 chunk 抽取并落库结构化标签。
     * @Logic: 使用传入词库版本覆盖删除旧标签，再写入本次匹配命中的去重标签；无命中时仅清理旧标签。
     * @Param: chunk 待打标子切片；dictionaryVersion 本次打标使用的词库版本。
     * @Return: 本次写入的新标签列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    @Transactional
    public List<ReportChunkTag> extractAndPersist(ReportChunk chunk, String dictionaryVersion) {
        // 组合章节和正文，章节路径对主题/风险/估值等信号有补充价值。
        String text = safeText(chunk.getSectionPath()) + "\n" + safeText(chunk.getChunkText());
        // 先抽取标签，再在事务内覆盖旧结果。
        List<ReportChunkTag> tags = extract(chunk, text, dictionaryVersion);
        // 重打标前删除当前版本旧标签，避免历史结果与最新结果混用。
        reportChunkTagMapper.deleteByChunkUidAndVersion(chunk.getChunkUid(), dictionaryVersion);
        // 逐条插入标签，当前标签数量通常较小，保持逻辑直观和错误定位清晰。
        for (ReportChunkTag tag : tags) {
            reportChunkTagMapper.insert(tag);
        }
        return tags;
    }

    /**
     * @Description: 为 chunk 构建标签对象但不写库。
     * @Logic: 词库匹配结果按 tagType+tagCode 去重，同一标签多词命中时保留首次命中。
     * @Param: chunk 待打标切片；text 待匹配文本；dictionaryVersion 词库版本。
     * @Return: 标签对象列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public List<ReportChunkTag> extract(ReportChunk chunk, String text, String dictionaryVersion) {
        // 使用 LinkedHashMap 保持词库和文本命中的稳定顺序。
        Map<String, ReportChunkTag> tags = new LinkedHashMap<>();
        // 当前时间统一用于本次抽取产生的所有标签。
        Instant now = Instant.now();
        // 扫描文本中的所有词库命中。
        for (TaxonomyMatch match : taxonomySnapshotService.match(text)) {
            String key = match.entry().tagType() + "|" + match.entry().tagCode();
            tags.putIfAbsent(key, buildTag(chunk, match, dictionaryVersion, now));
        }
        return List.copyOf(tags.values());
    }

    /**
     * @Description: 将词库命中转换为标签实体。
     * @Logic: 复制 chunk 定位、标签编码名称、置信度、来源和词库版本，供 Mapper 写入 MySQL 主数据。
     * @Param: chunk 待打标切片；match 词库命中；dictionaryVersion 词库版本；now 当前时间。
     * @Return: 标签实体。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private ReportChunkTag buildTag(ReportChunk chunk, TaxonomyMatch match, String dictionaryVersion, Instant now) {
        ReportChunkTag tag = new ReportChunkTag();
        tag.setReportId(chunk.getReportId());
        tag.setChunkUid(chunk.getChunkUid());
        tag.setTagType(match.entry().tagType());
        tag.setTagCode(match.entry().tagCode());
        tag.setTagName(match.entry().tagName());
        tag.setConfidence(resolveConfidence(match));
        tag.setSource(TAG_SOURCE_DICTIONARY);
        tag.setDictionaryVersion(dictionaryVersion);
        tag.setCreatedAt(now);
        tag.setUpdatedAt(now);
        return tag;
    }

    /**
     * @Description: 解析词库命中的标签置信度。
     * @Logic: 使用词条权重作为基础置信度；缺失时兜底为 1.0。
     * @Param: match 词库命中对象。
     * @Return: 标签置信度。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private BigDecimal resolveConfidence(TaxonomyMatch match) {
        return match.entry().weight() == null ? BigDecimal.ONE : match.entry().weight();
    }

    /**
     * @Description: 安全处理文本。
     * @Logic: null 转为空字符串，避免拼接匹配文本时出现空指针。
     * @Param: text 原始文本。
     * @Return: 非 null 文本。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private String safeText(String text) {
        return text == null ? "" : text;
    }
}
