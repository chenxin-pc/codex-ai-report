package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.ReportRetrievalService.RetrievedChunk;
import com.example.aimilvusweb.service.ResearchQueryAnchorService.QueryAnchors;
import org.springframework.ai.document.Document;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @Description: 召回候选业务加权排序组件。
 * @Logic: 在 Milvus fused score 基础上对 ticker、公司、作者、主题、行业和章节意图命中的候选加小幅 boost，并写入诊断分数。
 * @Param: 无。
 * @Return: 无（无状态排序组件）。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
public class RetrievalBusinessBoostRanker {

    /** 股票代码精确命中 boost。 */
    private static final double TICKER_BOOST = 0.18D;
    /** 公司名命中 boost。 */
    private static final double COMPANY_BOOST = 0.12D;
    /** 作者命中 boost。 */
    private static final double AUTHOR_BOOST = 0.10D;
    /** 主题命中 boost。 */
    private static final double THEME_BOOST = 0.08D;
    /** 行业命中 boost。 */
    private static final double INDUSTRY_BOOST = 0.06D;
    /** 章节意图命中 boost。 */
    private static final double SECTION_BOOST = 0.05D;

    /**
     * @Description: 对候选执行业务加权排序。
     * @Logic: 先计算标准化 fused score，再叠加 metadata 命中 boost，最后按 finalScore 降序稳定排序。
     * @Param: candidates 去重或重排后的候选；anchors query 锚点。
     * @Return: 加权排序后的候选。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public List<RetrievedChunk> rank(List<RetrievedChunk> candidates, QueryAnchors anchors) {
        // 空候选无需加权。
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        // 无锚点时只补充 normalizedScore 诊断并保持原顺序。
        if (anchors == null || !anchors.hasAnyAnchor()) {
            return candidates.stream().map(candidate -> withScores(candidate, 0D)).toList();
        }
        // 计算业务 boost 后按最终分数降序排序。
        return candidates.stream()
                .map(candidate -> withScores(candidate, boost(candidate.document(), anchors)))
                .sorted(Comparator.comparingDouble(this::finalScore).reversed())
                .toList();
    }

    /**
     * @Description: 为候选写入诊断分数并返回新候选。
     * @Logic: normalizedScore 表示归一化 Milvus fused score，businessBoostScore 表示业务加权，finalScore 表示最终排序分。
     * @Param: candidate 原始候选；boost 业务加权分。
     * @Return: 带最终分数的新候选。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private RetrievedChunk withScores(RetrievedChunk candidate, double boost) {
        // 读取并标准化原始召回分数。
        double normalizedScore = normalizeScore(candidate.score());
        // 计算最终排序分。
        double finalScore = normalizedScore + boost;
        // 将诊断分数写回 Document metadata，供后续响应或排障查看。
        candidate.document().getMetadata().put("normalizedScore", normalizedScore);
        // 写入业务加权分。
        candidate.document().getMetadata().put("businessBoostScore", boost);
        // 写入最终排序分。
        candidate.document().getMetadata().put("finalScore", finalScore);
        // 返回新的候选，让 PARENT 聚合的 max/avg score 使用最终排序分。
        return new RetrievedChunk(candidate.document(), finalScore, candidate.chunkText(), candidate.evidenceText(),
                candidate.contextType(), candidate.hitChildren(), candidate.hitCount(),
                candidate.maxScore(), candidate.averageScore(), candidate.truncated(), candidate.diagnosticOnly());
    }

    /**
     * @Description: 计算候选业务 boost。
     * @Logic: 对每类锚点只加一次分，避免同一类型多个词条导致分数膨胀。
     * @Param: document 候选文档；anchors query 锚点。
     * @Return: boost 分数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private double boost(Document document, QueryAnchors anchors) {
        // 读取 metadata 便于后续比较。
        Map<String, Object> metadata = document.getMetadata();
        // 初始化 boost。
        double boost = 0D;
        // 股票代码精确命中加权。
        boost += containsAny(metadataText(metadata, "ticker"), anchors.tickers()) ? TICKER_BOOST : 0D;
        // 公司名命中加权。
        boost += containsAny(metadataText(metadata, "companyName"), anchors.companyCodes()) ? COMPANY_BOOST : 0D;
        // 作者命中加权，多作者使用 authorText 的 |name| 形式判断。
        boost += containsAuthor(metadataText(metadata, "authorText"), anchors.authorNames()) ? AUTHOR_BOOST : 0D;
        // 报告级或 chunk 级主题命中加权。
        boost += (containsAny(metadataText(metadata, "reportThemeCode"), anchors.themeCodes())
                || containsAny(metadataText(metadata, "themeCode"), anchors.themeCodes())) ? THEME_BOOST : 0D;
        // 行业命中加权。
        boost += containsAny(metadataText(metadata, "industryCode"), anchors.industryCodes()) ? INDUSTRY_BOOST : 0D;
        // 章节意图命中加权。
        boost += matchesSectionIntent(metadataText(metadata, "sectionPath"), anchors.sectionIntents()) ? SECTION_BOOST : 0D;
        // 返回业务加权总分。
        return boost;
    }

    /**
     * @Description: 读取最终排序分。
     * @Logic: finalScore 已在 withScores 写入候选 score，缺失时回退 0。
     * @Param: candidate 候选。
     * @Return: 最终排序分。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private double finalScore(RetrievedChunk candidate) {
        // score 为空时按 0 排序。
        return candidate.score() == null ? 0D : candidate.score();
    }

    /**
     * @Description: 标准化 Milvus fused score。
     * @Logic: 0 到 1 的分数保持不变；大于 1 的分数用 score/(1+score) 压到 1 以内。
     * @Param: score 原始分数。
     * @Return: 归一化分数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private double normalizeScore(Double score) {
        // 缺失分数按 0 处理。
        if (score == null) {
            return 0D;
        }
        // 负分数归零。
        if (score <= 0D) {
            return 0D;
        }
        // 小于等于 1 的相似度分数直接使用。
        if (score <= 1D) {
            return score;
        }
        // 大于 1 的 ranker 分数压缩到 0 到 1 区间。
        return score / (1D + score);
    }

    /**
     * @Description: 判断字段是否命中任一候选值。
     * @Logic: 忽略大小写比较，支持 ticker、公司、主题和行业等单值 metadata。
     * @Param: fieldValue metadata 字段值；values 候选锚点。
     * @Return: 命中时返回 true。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private boolean containsAny(String fieldValue, List<String> values) {
        // 空字段或空锚点都无法命中。
        if (fieldValue.isBlank() || values == null || values.isEmpty()) {
            return false;
        }
        // 规范化字段值。
        String normalizedFieldValue = normalize(fieldValue);
        // 遍历候选值。
        for (String value : values) {
            // 候选值相等或包含时视为命中。
            if (!normalize(value).isBlank() && normalizedFieldValue.contains(normalize(value))) {
                return true;
            }
        }
        // 所有候选值都未命中。
        return false;
    }

    /**
     * @Description: 判断作者字段是否命中任一作者锚点。
     * @Logic: authorText 使用 |normalizedAuthor| 包裹，避免短姓名误匹配其他字符串。
     * @Param: authorText 多作者文本；authorNames 规范化作者名列表。
     * @Return: 命中时返回 true。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private boolean containsAuthor(String authorText, List<String> authorNames) {
        // 空作者字段或空作者锚点都无法命中。
        if (authorText.isBlank() || authorNames == null || authorNames.isEmpty()) {
            return false;
        }
        // 规范化多作者文本。
        String normalizedAuthorText = normalize(authorText);
        // 遍历作者锚点。
        for (String authorName : authorNames) {
            // 按 |作者| 完整片段匹配。
            if (normalizedAuthorText.contains("|" + normalize(authorName) + "|")) {
                return true;
            }
        }
        // 所有作者都未命中。
        return false;
    }

    /**
     * @Description: 判断章节路径是否命中章节意图。
     * @Logic: 将章节意图编码映射为中文关键词后做 contains 判断。
     * @Param: sectionPath 章节路径；sectionIntents 章节意图列表。
     * @Return: 命中时返回 true。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private boolean matchesSectionIntent(String sectionPath, List<String> sectionIntents) {
        // 空章节或空意图都无法命中。
        if (sectionPath.isBlank() || sectionIntents == null || sectionIntents.isEmpty()) {
            return false;
        }
        // 规范化章节路径。
        String normalizedSectionPath = normalize(sectionPath);
        // 风险章节意图匹配风险类标题。
        if (sectionIntents.contains("RISK") && (normalizedSectionPath.contains("风险") || normalizedSectionPath.contains("不确定性"))) {
            return true;
        }
        // 投资逻辑意图匹配逻辑、驱动和投资要点类标题。
        if (sectionIntents.contains("INVESTMENT_LOGIC")
                && (normalizedSectionPath.contains("逻辑") || normalizedSectionPath.contains("驱动") || normalizedSectionPath.contains("投资要点"))) {
            return true;
        }
        // 估值意图匹配估值、目标价和盈利预测类标题。
        return sectionIntents.contains("VALUATION")
                && (normalizedSectionPath.contains("估值") || normalizedSectionPath.contains("目标价") || normalizedSectionPath.contains("盈利预测"));
    }

    /**
     * @Description: 读取 metadata 文本字段。
     * @Logic: null 转为空字符串并裁剪空白。
     * @Param: metadata 文档 metadata；key 字段名。
     * @Return: 字段文本。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String metadataText(Map<String, Object> metadata, String key) {
        // 读取原始字段值。
        Object value = metadata.get(key);
        // null 统一为空字符串。
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * @Description: 规范化比较文本。
     * @Logic: 移除空白并转小写，降低大小写和空白格式差异。
     * @Param: value 原始文本。
     * @Return: 规范化文本。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String normalize(String value) {
        // null 值按空字符串处理。
        if (value == null) {
            return "";
        }
        // 去空白并转小写。
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
