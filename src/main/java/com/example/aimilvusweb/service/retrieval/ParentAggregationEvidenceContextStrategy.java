package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.service.ReportRetrievalService.EvidenceContextType;
import com.example.aimilvusweb.service.ReportRetrievalService.RetrievedChild;
import com.example.aimilvusweb.service.ReportRetrievalService.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 按 PARENT 聚合召回候选的证据上下文策略。
 * @Logic: 按 parentChunkUid 聚合命中 CHILD，依据命中数量、相关性和原始顺序排序，并在 token 预算内构建 FULL/TRUNCATED/CHILD_WINDOW/CHILD_FALLBACK 证据。
 * @Param: 无。
 * @Return: 无（策略组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class ParentAggregationEvidenceContextStrategy implements EvidenceContextStrategy {

    /** 研报切片 Mapper，用于回查 PARENT 和同父 CHILD。 */
    private final ReportChunkMapper reportChunkMapper;
    /** 研报质量配置，用于读取 PARENT 聚合数量和 token 预算。 */
    private final ReportQualityProperties reportQualityProperties;
    /** 召回候选去重组件，用于无 parentChunkUid 分组时构造稳定身份 key。 */
    private final RetrievedChunkDeduplicator deduplicator;

    /**
     * @Description: 初始化 PARENT 聚合证据策略。
     * @Logic: 保存 Mapper、质量配置和去重组件，构建分组 key、回查上下文和应用 token 预算时复用。
     * @Param: reportChunkMapper 切片 Mapper；reportQualityProperties 质量配置；deduplicator 候选去重组件。
     * @Return: 无（仅初始化策略依赖）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public ParentAggregationEvidenceContextStrategy(ReportChunkMapper reportChunkMapper,
                                                    ReportQualityProperties reportQualityProperties,
                                                    RetrievedChunkDeduplicator deduplicator) {
        this.reportChunkMapper = reportChunkMapper;
        this.reportQualityProperties = reportQualityProperties;
        this.deduplicator = deduplicator;
    }

    /**
     * @Description: 按 PARENT 聚合构建最终推荐证据。
     * @Logic: 先按 parentChunkUid 分组并排序，再按组数和总 evidence token 预算依次选择可用证据。
     * @Param: candidates 去重和可选重排后的 CHILD 候选；finalTopK 最终返回数量上限。
     * @Return: PARENT 聚合后的最终证据列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    @Override
    public List<RetrievedChunk> build(List<RetrievedChunk> candidates, int finalTopK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<ParentEvidenceAccumulator> sortedGroups = groupAndSort(candidates);
        int maxGroups = Math.max(1, Math.min(finalTopK, reportQualityProperties.getRetrieval().getMaxParentEvidenceGroups()));
        int remainingTokens = Math.max(1, reportQualityProperties.getRetrieval().getTotalEvidenceContextTokens());
        List<RetrievedChunk> selected = new ArrayList<>();
        for (ParentEvidenceAccumulator group : sortedGroups) {
            if (selected.size() >= maxGroups || remainingTokens <= 0) {
                break;
            }
            RetrievedChunk retrievedChunk = buildParentEvidenceGroup(group, remainingTokens);
            if (retrievedChunk.evidenceText().isBlank()) {
                continue;
            }
            selected.add(retrievedChunk);
            remainingTokens -= Math.max(1, SemanticChunkUtils.estimateTokens(retrievedChunk.evidenceText()));
        }
        return selected;
    }

    /**
     * @Description: 按 PARENT 分组并排序候选。
     * @Logic: parentChunkUid 存在时按 PARENT 聚合，缺失时按 child fallback 独立成组；排序优先命中数，其次最高分、平均分和首次召回顺序。
     * @Param: candidates 去重和可选重排后的候选。
     * @Return: 排序后的 PARENT 聚合组。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private List<ParentEvidenceAccumulator> groupAndSort(List<RetrievedChunk> candidates) {
        Map<String, ParentEvidenceAccumulator> groups = new LinkedHashMap<>();
        for (int rank = 0; rank < candidates.size(); rank++) {
            RetrievedChunk candidate = candidates.get(rank);
            String parentChunkUid = RetrievalMetadataUtils.metadataText(candidate.document(), "parentChunkUid");
            String groupKey = parentChunkUid.isBlank() ? "child:" + deduplicator.identityDedupKey(candidate) + ":" + rank : "parent:" + parentChunkUid;
            int firstRank = rank;
            ParentEvidenceAccumulator accumulator = groups.computeIfAbsent(groupKey, ignored -> new ParentEvidenceAccumulator(parentChunkUid, firstRank));
            accumulator.add(candidate, rank);
        }
        return groups.values().stream()
                .sorted(Comparator.comparingInt(ParentEvidenceAccumulator::hitCount).reversed()
                        .thenComparing(Comparator.comparingDouble(ParentEvidenceAccumulator::maxScore).reversed())
                        .thenComparing(Comparator.comparingDouble(ParentEvidenceAccumulator::averageScore).reversed())
                        .thenComparingInt(ParentEvidenceAccumulator::firstRank))
                .toList();
    }

    /**
     * @Description: 将单个 PARENT 聚合组转换为最终证据。
     * @Logic: PARENT 缺失时使用命中 CHILD 文本；PARENT 在预算内时完整使用；中等长度时截断；超长时构建 CHILD 覆盖窗口。
     * @Param: group PARENT 聚合组；remainingTokens 当前剩余总 token 预算。
     * @Return: 当前聚合组对应的 RetrievedChunk。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private RetrievedChunk buildParentEvidenceGroup(ParentEvidenceAccumulator group, int remainingTokens) {
        RetrievedChunk representative = group.representative();
        String parentChunkUid = group.parentChunkUid();
        int parentBudget = Math.max(1, Math.min(reportQualityProperties.getRetrieval().getMaxParentContextTokens(), remainingTokens));
        List<RetrievedChild> hitChildren = group.hitChildren();
        if (parentChunkUid.isBlank()) {
            String fallbackText = RetrievalTextLimiter.limitTokens(joinHitChildTexts(group.hits()), parentBudget);
            return aggregateResult(representative, fallbackText, EvidenceContextType.CHILD_FALLBACK, hitChildren, true);
        }

        ReportChunk parentChunk = reportChunkMapper.selectByChunkUid(parentChunkUid);
        if (parentChunk == null || parentChunk.getChunkText() == null || parentChunk.getChunkText().isBlank()) {
            String fallbackText = RetrievalTextLimiter.limitTokens(joinHitChildTexts(group.hits()), parentBudget);
            return aggregateResult(representative, fallbackText, EvidenceContextType.CHILD_FALLBACK, hitChildren, true);
        }

        String parentText = parentChunk.getChunkText();
        int parentTokens = SemanticChunkUtils.estimateTokens(parentText);
        if (parentTokens <= parentBudget && parentTokens <= reportQualityProperties.getRetrieval().getLargeParentContextTokens()) {
            return aggregateResult(representative, parentText, EvidenceContextType.FULL_PARENT, hitChildren, false);
        }
        if (parentTokens <= reportQualityProperties.getRetrieval().getLargeParentContextTokens()) {
            return aggregateResult(representative, RetrievalTextLimiter.limitTokens(parentText, parentBudget), EvidenceContextType.TRUNCATED_PARENT, hitChildren, true);
        }
        String childWindow = buildChildWindowContext(parentChunkUid, group.hits(), parentBudget);
        return aggregateResult(representative, childWindow, EvidenceContextType.CHILD_WINDOW, hitChildren, true);
    }

    /**
     * @Description: 构建聚合证据结果对象。
     * @Logic: 复用代表 CHILD 的 Document 和 chunkText，写入聚合上下文、命中明细、最高分、平均分和截断状态。
     * @Param: representative 代表候选；evidenceText 生成上下文；contextType 上下文来源；hitChildren 命中 CHILD 明细；truncated 是否截断或退化。
     * @Return: 聚合后的 RetrievedChunk。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private RetrievedChunk aggregateResult(RetrievedChunk representative,
                                           String evidenceText,
                                           EvidenceContextType contextType,
                                           List<RetrievedChild> hitChildren,
                                           boolean truncated) {
        double maxScore = hitChildren.stream().map(RetrievedChild::score).mapToDouble(score -> score == null ? 0D : score).max().orElse(0D);
        double averageScore = hitChildren.stream().map(RetrievedChild::score).mapToDouble(score -> score == null ? 0D : score).average().orElse(0D);
        return new RetrievedChunk(representative.document(), representative.score(), representative.chunkText(), evidenceText,
                contextType, hitChildren, hitChildren.size(), maxScore, averageScore, truncated, false);
    }

    /**
     * @Description: 构建命中 CHILD 及相邻 CHILD 的覆盖窗口。
     * @Logic: 超长 PARENT 不直接整段入 Prompt，优先保留命中的 CHILD，再补充前后邻域并受 token 预算控制。
     * @Param: parentChunkUid PARENT UID；hits 当前 PARENT 组命中 CHILD；maxTokens 窗口 token 上限。
     * @Return: CHILD 覆盖窗口文本。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private String buildChildWindowContext(String parentChunkUid, List<RetrievedChunk> hits, int maxTokens) {
        List<ReportChunk> siblings = reportChunkMapper.selectChildrenByParentChunkUid(parentChunkUid);
        if (siblings == null || siblings.isEmpty()) {
            return RetrievalTextLimiter.limitTokens(joinHitChildTexts(hits), maxTokens);
        }
        Set<String> hitChunkUids = new HashSet<>();
        for (RetrievedChunk hit : hits) {
            String chunkUid = RetrievalMetadataUtils.metadataText(hit.document(), "chunkUid");
            if (!chunkUid.isBlank()) {
                hitChunkUids.add(chunkUid);
            }
        }
        if (hitChunkUids.isEmpty()) {
            return RetrievalTextLimiter.limitTokens(joinHitChildTexts(hits), maxTokens);
        }

        int neighborCount = Math.max(0, reportQualityProperties.getRetrieval().getChildWindowNeighborCount());
        Set<Integer> selectedIndexes = new HashSet<>();
        for (int i = 0; i < siblings.size(); i++) {
            ReportChunk sibling = siblings.get(i);
            if (hitChunkUids.contains(sibling.getChunkUid())) {
                int start = Math.max(0, i - neighborCount);
                int end = Math.min(siblings.size() - 1, i + neighborCount);
                for (int selectedIndex = start; selectedIndex <= end; selectedIndex++) {
                    selectedIndexes.add(selectedIndex);
                }
            }
        }
        if (selectedIndexes.isEmpty()) {
            return RetrievalTextLimiter.limitTokens(joinHitChildTexts(hits), maxTokens);
        }

        StringBuilder builder = new StringBuilder();
        int tokens = 0;
        for (int i = 0; i < siblings.size(); i++) {
            if (!selectedIndexes.contains(i)) {
                continue;
            }
            String text = siblings.get(i).getChunkText();
            if (text == null || text.isBlank()) {
                continue;
            }
            int textTokens = SemanticChunkUtils.estimateTokens(text);
            if (tokens > 0 && tokens + textTokens > maxTokens) {
                break;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(text.trim());
            tokens += textTokens;
        }
        if (builder.isEmpty()) {
            return RetrievalTextLimiter.limitTokens(joinHitChildTexts(hits), maxTokens);
        }
        return builder.toString();
    }

    /**
     * @Description: 拼接命中 CHILD 文本。
     * @Logic: 父切片缺失或 CHILD 窗口无法构建时使用实际命中 CHILD 作为兜底证据。
     * @Param: hits 当前聚合组命中的 CHILD 候选。
     * @Return: 拼接后的 CHILD 文本。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private String joinHitChildTexts(List<RetrievedChunk> hits) {
        StringBuilder builder = new StringBuilder();
        for (RetrievedChunk hit : hits) {
            if (hit.chunkText() == null || hit.chunkText().isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(hit.chunkText().trim());
        }
        return builder.toString();
    }

    /**
     * @Description: PARENT 聚合过程中的临时累加器。
     * @Logic: 保存同一 parentChunkUid 下命中的 CHILD 候选和原始召回顺序，用于排序和上下文构建。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private static class ParentEvidenceAccumulator {
        /** 父切片 UID，缺失时为空字符串。 */
        private final String parentChunkUid;
        /** 当前聚合组第一次出现的召回顺序。 */
        private final int firstRank;
        /** 当前聚合组内的命中 CHILD 候选。 */
        private final List<RetrievedChunk> hits = new ArrayList<>();
        /** 当前聚合组内每个 CHILD 的原始召回顺序。 */
        private final List<Integer> ranks = new ArrayList<>();

        private ParentEvidenceAccumulator(String parentChunkUid, int firstRank) {
            this.parentChunkUid = parentChunkUid == null ? "" : parentChunkUid;
            this.firstRank = firstRank;
        }

        private void add(RetrievedChunk hit, int rank) {
            hits.add(hit);
            ranks.add(rank);
        }

        private String parentChunkUid() {
            return parentChunkUid;
        }

        private int firstRank() {
            return firstRank;
        }

        private int hitCount() {
            return hits.size();
        }

        private double maxScore() {
            return hits.stream().map(RetrievedChunk::score).mapToDouble(score -> score == null ? 0D : score).max().orElse(0D);
        }

        private double averageScore() {
            return hits.stream().map(RetrievedChunk::score).mapToDouble(score -> score == null ? 0D : score).average().orElse(0D);
        }

        private RetrievedChunk representative() {
            return hits.stream()
                    .max(Comparator.comparingDouble(hit -> hit.score() == null ? 0D : hit.score()))
                    .orElse(hits.get(0));
        }

        private List<RetrievedChunk> hits() {
            return hits;
        }

        private List<RetrievedChild> hitChildren() {
            List<RetrievedChild> children = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                RetrievedChunk hit = hits.get(i);
                children.add(new RetrievedChild(hit.document(), hit.score(), hit.chunkText(), ranks.get(i)));
            }
            return List.copyOf(children);
        }
    }
}
