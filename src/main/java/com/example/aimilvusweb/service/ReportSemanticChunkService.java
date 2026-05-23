package com.example.aimilvusweb.service;

import com.alibaba.fastjson2.JSON;
import com.example.aimilvusweb.common.llm.QwenClient;
import com.example.aimilvusweb.common.prompt.PromptTemplateService;
import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ChunkingOptions;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ParagraphAtom;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ReportSemanticChunks;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.SemanticSegment;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.dto.LlmChunkPlanRespDTO;
import com.example.aimilvusweb.dto.LlmChunkSegmentRespDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
/**
 * @Description: ReportSemanticChunkService类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class ReportSemanticChunkService {

    private static final Logger log = LoggerFactory.getLogger(ReportSemanticChunkService.class);
    private static final int MAX_SEGMENT_TOKENS = 6000;
    private static final double MIN_CONFIDENCE = 0.35D;

    private final QwenClient qwenClient;
    private final PromptTemplateService promptTemplateService;
    private final ReportQualityProperties reportQualityProperties;

    /**
     * @Description: 初始化ReportSemanticChunkService依赖与运行所需组件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    @Autowired
    public ReportSemanticChunkService(QwenClient qwenClient,
                                      PromptTemplateService promptTemplateService,
                                      ReportQualityProperties reportQualityProperties) {
        this.qwenClient = qwenClient;
        this.promptTemplateService = promptTemplateService;
        this.reportQualityProperties = reportQualityProperties;
    }

    ReportSemanticChunkService(QwenClient qwenClient, PromptTemplateService promptTemplateService) {
        this(qwenClient, promptTemplateService, new ReportQualityProperties());
    }

    /**
     * @Description: 执行文本切片并返回切片结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public ReportSemanticChunks chunk(String parsedText) {
        List<ParagraphAtom> atoms = SemanticChunkUtils.atomizeReportParagraphs(parsedText);
        return chunk(atoms);
    }

    /**
     * @Description: 执行文本切片并返回切片结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public ReportSemanticChunks chunk(List<ParagraphAtom> atoms) {
        if (atoms.isEmpty()) {
            return new ReportSemanticChunks(List.of(), List.of());
        }

        List<SemanticSegment> plannedSegments = planSemanticSegments(atoms);
        List<SemanticSegment> repairedSegments = validateAndRepairSegments(atoms, plannedSegments);
        if (repairedSegments.isEmpty()) {
            throw new LlmSemanticChunkException("LLM semantic chunk failed: no valid segments after boundary validation");
        }

        ReportSemanticChunks chunks = SemanticChunkUtils.chunkReportBySegments(atoms, repairedSegments, chunkingOptions());
        if (chunks.isEmpty()) {
            throw new LlmSemanticChunkException("LLM semantic chunk failed: no chunks generated from validated segments");
        }
        return chunks;
    }

    /**
     * @Description: 执行文本切片并返回切片结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private ChunkingOptions chunkingOptions() {
        ReportQualityProperties.Chunk chunk = reportQualityProperties.getChunk();
        return new ChunkingOptions(
                chunk.getChildTargetTokens(),
                chunk.getChildMaxTokens(),
                chunk.getParentTargetTokens(),
                chunk.getParentMaxTokens(),
                chunk.getOverlapTokens()
        );
    }

    /**
     * @Description: 执行planSemanticSegments相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private List<SemanticSegment> planSemanticSegments(List<ParagraphAtom> atoms) {
        // 读取 chunk 相关运行参数，支持通过配置动态调优分批策略。
        ReportQualityProperties.Chunk chunk = reportQualityProperties.getChunk();
        // LLM 单批最大段落数，防止段落维度请求过长。
        int maxParagraphsPerBatch = Math.max(1, chunk.getLlmMaxParagraphsPerBatch());
        // LLM 单批最大 token，防止上下文窗口溢出。
        int maxTokensPerBatch = Math.max(512, chunk.getLlmMaxTokensPerBatch());
        // 批次重叠段落数目标值，用于跨批次语义衔接。
        int overlapParagraphs = Math.max(0, chunk.getLlmOverlapParagraphs());
        // 批次重叠 token 上限，防止重叠内容本身过大。
        int overlapMaxTokens = Math.max(64, chunk.getLlmOverlapMaxTokens());

        // 先将原始段落原子转为规划用原子，并对单段超限场景做临时子切分。
        List<PlanningAtom> planningAtoms = buildPlanningAtoms(atoms, maxTokensPerBatch);
        // 若无可用规划数据，直接返回空边界。
        if (planningAtoms.isEmpty()) {
            return List.of();
        }
        // 构建虚拟段落ID -> 原始段落ID 映射，供 LLM 结果回映射使用。
        Map<Integer, Integer> virtualToOriginParagraphId = new LinkedHashMap<>();
        // 遍历所有规划原子，写入映射表。
        for (PlanningAtom atom : planningAtoms) {
            // 一个虚拟段落只对应一个原始段落，后写不会改变语义。
            virtualToOriginParagraphId.put(atom.virtualParagraphId(), atom.originParagraphId());
        }
        // 收集所有批次返回的语义边界，后续统一去重。
        List<SemanticSegment> segments = new ArrayList<>();
        // 起始游标，表示当前批次在规划原子列表中的开始位置。
        int start = 0;
        // 循环推进所有批次，直到覆盖完整段落流。
        while (start < planningAtoms.size()) {
            // 按“双阈值”计算当前批次结束位置（段落数/token 任一触发即截断）。
            int endExclusive = resolveBatchEnd(planningAtoms, start, maxParagraphsPerBatch, maxTokensPerBatch);
            // 截取当前批次的规划原子，作为本次 LLM 输入。
            List<PlanningAtom> batch = planningAtoms.subList(start, endExclusive);
            // 请求 LLM 返回当前批次语义边界。
            LlmChunkPlanRespDTO plan = requestChunkPlan(batch);
            // 模型返回空对象，直接视为失败并抛异常。
            if (plan == null) {
                throw new LlmSemanticChunkException("LLM semantic chunk failed: model returned null plan for paragraph batch " + (start + 1) + "-" + endExclusive);
            }
            // 模型返回无边界，同样判定为失败，避免静默成功。
            if (plan.segments() == null || plan.segments().isEmpty()) {
                throw new LlmSemanticChunkException("LLM semantic chunk failed: model returned empty segments for paragraph batch " + (start + 1) + "-" + endExclusive);
            }
            // 遍历模型返回的每个边界片段并回映射到原始段落体系。
            for (LlmChunkSegmentRespDTO segment : plan.segments()) {
                // 仅处理起止边界完整的片段，跳过脏数据。
                if (segment.startParagraphId() != null && segment.endParagraphId() != null) {
                    // 将虚拟起点映射回原始段落ID。
                    Integer mappedStart = virtualToOriginParagraphId.get(segment.startParagraphId());
                    // 将虚拟终点映射回原始段落ID。
                    Integer mappedEnd = virtualToOriginParagraphId.get(segment.endParagraphId());
                    // 映射缺失说明模型返回了批次外 ID，直接丢弃该片段。
                    if (mappedStart == null || mappedEnd == null) {
                        continue;
                    }
                    // 归一化起止顺序，防止 start/end 反转。
                    int normalizedStart = Math.min(mappedStart, mappedEnd);
                    // 归一化终点，确保 start <= end。
                    int normalizedEnd = Math.max(mappedStart, mappedEnd);
                    // 写入回映射后的语义边界，进入后续统一去重流程。
                    segments.add(new SemanticSegment(
                            normalizedStart,
                            normalizedEnd,
                            segment.topic(),
                            segment.segmentType(),
                            segment.confidence() == null ? 0D : segment.confidence()
                    ));
                }
            }
            // 当前批次已覆盖到末尾，直接结束循环。
            if (endExclusive >= planningAtoms.size()) {
                break;
            }
            // 计算下一批起点：从当前批末回退重叠窗口，实现跨批语义连续。
            int nextStart = resolveNextBatchStart(planningAtoms, start, endExclusive, overlapParagraphs, overlapMaxTokens);
            // 兜底保护：若回退后起点未前进，则强制前移 1，避免死循环。
            start = nextStart <= start ? start + 1 : nextStart;
        }
        // 对所有批次边界做去重合并，消除重叠窗口带来的重复/包含片段。
        return deduplicateBoundarySegments(segments);
    }

    /**
     * @Description: 构建 LLM 规划用段落列表，处理单段超 token 的临时子切分。
     * @Logic: 正常段落保持原样；当单段 token 超过批次上限时按句级切分为多个临时段，并分配虚拟段落ID，后续再回映射到原段落ID。
     * @Param: atoms 原始段落原子；maxTokensPerBatch LLM 单批最大 token。
     * @Return: 规划阶段段落列表。
     * @author: cx
     * @Date: 2026-05-22 22:05:00
     */
    private List<PlanningAtom> buildPlanningAtoms(List<ParagraphAtom> atoms, int maxTokensPerBatch) {
        // 规划用原子列表：可能包含由超长段临时拆出的子片段。
        List<PlanningAtom> planningAtoms = new ArrayList<>();
        // 虚拟段落ID从1开始递增，专供 LLM 批次输入使用。
        int virtualParagraphId = 1;
        // 遍历原始段落原子，逐个判断是否超限。
        for (ParagraphAtom atom : atoms) {
            // token 至少按 1 处理，规避异常零值。
            int tokenCount = Math.max(1, atom.tokenCount());
            // 段落未超 token 上限，直接作为一个规划原子。
            if (tokenCount <= maxTokensPerBatch) {
                planningAtoms.add(new PlanningAtom(
                        virtualParagraphId++,
                        atom.paragraphId(),
                        atom.pageNumber(),
                        atom.sectionPath(),
                        atom.text(),
                        tokenCount));
                continue;
            }
            // 单段超限：按句级/长度临时拆分，保证每个片段可被 LLM 接收。
            List<String> pieces = splitOverlongParagraph(atom.text(), maxTokensPerBatch);
            // 将每个子片段包装为规划原子，并保留 originParagraphId 用于回映射。
            for (String piece : pieces) {
                planningAtoms.add(new PlanningAtom(
                        virtualParagraphId++,
                        atom.paragraphId(),
                        atom.pageNumber(),
                        atom.sectionPath(),
                        piece,
                        Math.max(1, SemanticChunkUtils.estimateTokens(piece))));
            }
        }
        // 返回最终规划原子序列（虚拟ID连续且可回溯原段落）。
        return planningAtoms;
    }

    private int resolveBatchEnd(List<PlanningAtom> planningAtoms,
                                int start,
                                int maxParagraphsPerBatch,
                                int maxTokensPerBatch) {
        // endExclusive 指向当前批次“开区间终点”。
        int endExclusive = start;
        // 当前批次累计 token 计数器。
        int tokenSum = 0;
        // 逐段尝试扩展批次，直到触发段落数/token 任一上限。
        while (endExclusive < planningAtoms.size() && (endExclusive - start) < maxParagraphsPerBatch) {
            // 候选段落是当前 end 指向的原子。
            PlanningAtom candidate = planningAtoms.get(endExclusive);
            // 非首段且加入后超 token 上限，则停止扩展本批次。
            if (endExclusive > start && tokenSum + candidate.tokenCount() > maxTokensPerBatch) {
                break;
            }
            // 累加候选段 token，确认纳入当前批次。
            tokenSum += candidate.tokenCount();
            // 终点后移，继续尝试纳入下一段。
            endExclusive++;
        }
        // 至少保证每批次包含 1 段，避免异常配置导致空批次。
        return Math.max(start + 1, endExclusive);
    }

    private int resolveNextBatchStart(List<PlanningAtom> planningAtoms,
                                      int start,
                                      int endExclusive,
                                      int overlapParagraphs,
                                      int overlapMaxTokens) {
        // 已回退的重叠段数计数。
        int overlapCount = 0;
        // 已回退的重叠 token 计数。
        int overlapTokens = 0;
        // 从当前批次末尾开始向前回退，构建重叠窗口。
        int cursor = endExclusive - 1;
        // 同时受“重叠段数上限”和“重叠token上限”约束。
        while (cursor >= start && overlapCount < overlapParagraphs) {
            // 计算若纳入该段后的重叠 token 总量。
            int nextTokenSum = overlapTokens + planningAtoms.get(cursor).tokenCount();
            // 若超过重叠 token 预算，则停止回退。
            if (nextTokenSum > overlapMaxTokens) {
                break;
            }
            // 更新重叠 token 总量。
            overlapTokens = nextTokenSum;
            // 重叠段计数 +1。
            overlapCount++;
            // 游标继续前移，尝试扩大重叠窗口。
            cursor--;
        }
        // 下一批起点 = 当前批次终点 - 实际重叠段数。
        return endExclusive - overlapCount;
    }

    private List<String> splitOverlongParagraph(String text, int maxTokensPerBatch) {
        // 先做基础清洗，避免空白文本进入拆分流程。
        String normalized = text == null ? "" : text.trim();
        // 极端空白兜底：返回占位文本，避免后续空列表。
        if (normalized.isBlank()) {
            return List.of("空白段落");
        }
        // 先按句末标点做句级切分，优先保持语义完整。
        String[] sentenceParts = normalized.split("(?<=[。！？；.!?;])\\s+");
        // 存放最终可发送给 LLM 的子片段。
        List<String> pieces = new ArrayList<>();
        // 当前正在拼接的片段缓冲区。
        StringBuilder current = new StringBuilder();
        // 遍历句级片段，按 token 预算装箱。
        for (String part : sentenceParts) {
            // 去除前后空白，规避空句干扰。
            String sentence = part == null ? "" : part.trim();
            // 空句直接跳过。
            if (sentence.isBlank()) {
                continue;
            }
            // 单句本身超限：先落盘已有缓冲，再走长度硬切分兜底。
            if (SemanticChunkUtils.estimateTokens(sentence) > maxTokensPerBatch) {
                // 先将已有缓冲写入结果，避免顺序错乱。
                if (!current.isEmpty()) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                // 对超长单句按长度切分，确保每段都可被模型接受。
                pieces.addAll(splitByLength(sentence, maxTokensPerBatch));
                continue;
            }
            // 缓冲不为空时，尝试把当前句合并进现有片段。
            if (!current.isEmpty()) {
                // 预演合并文本，评估合并后 token 是否超限。
                String merged = current + " " + sentence;
                // 合并后超限：先落当前缓冲，再以当前句开启新片段。
                if (SemanticChunkUtils.estimateTokens(merged) > maxTokensPerBatch) {
                    pieces.add(current.toString());
                    current.setLength(0);
                    current.append(sentence);
                } else {
                    // 合并未超限：继续在同一片段累积上下文。
                    current.append(" ").append(sentence);
                }
            } else {
                // 缓冲为空，当前句作为新片段起点。
                current.append(sentence);
            }
        }
        // 循环结束后若缓冲非空，补写最后一个片段。
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        // 兜底：若所有拆分都异常为空，回退到原文本。
        return pieces.isEmpty() ? List.of(normalized) : pieces;
    }

    private List<String> splitByLength(String text, int maxTokensPerBatch) {
        // 存放硬切分后的文本片段。
        List<String> segments = new ArrayList<>();
        // 经验换算：中文语境下 char 大约是 token 的 1~2 倍，这里取 2 倍作为保守上界。
        int maxChars = Math.max(80, maxTokensPerBatch * 2);
        // 按固定字符窗口切分，保证线性复杂度和稳定性。
        for (int start = 0; start < text.length(); start += maxChars) {
            // 当前片段终点，最后一段允许不足窗口长度。
            int end = Math.min(text.length(), start + maxChars);
            // 截取并写入结果。
            segments.add(text.substring(start, end));
        }
        // 返回长度切分结果。
        return segments;
    }

    /**
     * @Description: 对跨批次重叠窗口产生的重复或包含边界进行去重合并。
     * @Logic: 先按起止段落与置信度排序；对相同边界保留高置信度；对同类型且被覆盖的低置信度边界进行淘汰，降低重叠窗口重复噪声。
     * @Param: segments 原始语义边界列表。
     * @Return: 去重后的语义边界列表。
     * @author: cx
     * @Date: 2026-05-22 00:02:00
     */
    private List<SemanticSegment> deduplicateBoundarySegments(List<SemanticSegment> segments) {
        // 空输入直接返回，避免无意义处理。
        if (segments.isEmpty()) {
            return List.of();
        }
        // 先按起点/终点排序，再按置信度降序，便于后续去重时优先保留高质量边界。
        List<SemanticSegment> sorted = segments.stream()
                .sorted(Comparator.comparingInt(SemanticSegment::startParagraphId)
                        .thenComparingInt(SemanticSegment::endParagraphId)
                        .thenComparing(Comparator.comparingDouble(SemanticSegment::confidence).reversed()))
                .toList();
        // merged 保存最终去重后的边界列表。
        List<SemanticSegment> merged = new ArrayList<>();
        // 逐个处理候选边界。
        for (SemanticSegment candidate : sorted) {
            // 查找是否已存在“完全相同起止区间”的边界。
            SemanticSegment duplicate = merged.stream()
                    .filter(existing -> existing.startParagraphId() == candidate.startParagraphId()
                            && existing.endParagraphId() == candidate.endParagraphId())
                    .findFirst()
                    .orElse(null);
            // 命中完全重复区间时，仅保留置信度更高者。
            if (duplicate != null) {
                if (candidate.confidence() > duplicate.confidence()) {
                    merged.remove(duplicate);
                    merged.add(candidate);
                }
                continue;
            }
            // 判断当前候选是否已被“同类型 + 高置信度 + 更大覆盖区间”包含。
            boolean coveredByHigher = merged.stream()
                    .anyMatch(existing -> existing.startParagraphId() <= candidate.startParagraphId()
                            && existing.endParagraphId() >= candidate.endParagraphId()
                            && sameSemanticType(existing, candidate)
                            && existing.confidence() >= candidate.confidence());
            // 未被高质量覆盖则保留该候选边界。
            if (!coveredByHigher) {
                merged.add(candidate);
            }
        }
        // 最终结果按起点升序返回，供后续边界修复逻辑处理。
        return merged.stream()
                .sorted(Comparator.comparingInt(SemanticSegment::startParagraphId))
                .toList();
    }

    /**
     * @Description: 判断两个语义边界是否属于同一语义类型。
     * @Logic: 对 segmentType 进行大小写与空值归一化后比较；用于边界去重合并时判定可替换关系。
     * @Param: left 左侧语义边界；right 右侧语义边界。
     * @Return: true 表示语义类型一致；false 表示不一致。
     * @author: cx
     * @Date: 2026-05-22 00:02:00
     */
    private boolean sameSemanticType(SemanticSegment left, SemanticSegment right) {
        String leftType = left.segmentType() == null ? "" : left.segmentType().trim().toUpperCase();
        String rightType = right.segmentType() == null ? "" : right.segmentType().trim().toUpperCase();
        return leftType.equals(rightType);
    }

    /**
     * @Description: 向外部服务发送请求并处理响应。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private LlmChunkPlanRespDTO requestChunkPlan(List<PlanningAtom> atoms) {
        try {
            String systemPrompt = promptTemplateService.loadTemplate("prompts/chunk-boundary-system-prompt.txt");
            String userPrompt = promptTemplateService.render("prompts/chunk-boundary-user-prompt.txt",
                    Map.of("paragraphs", toParagraphJson(atoms)));
            return qwenClient.chatForEntity(systemPrompt, userPrompt, LlmChunkPlanRespDTO.class);
        } catch (Exception e) {
            log.warn("LLM semantic chunk plan request failed", e);
            if (e instanceof LlmSemanticChunkException chunkException) {
                throw chunkException;
            }
            throw new LlmSemanticChunkException("LLM semantic chunk failed: model request error - " + e.getMessage(), e);
        }
    }

    /**
     * @Description: 执行对象到目标格式的转换。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String toParagraphJson(List<PlanningAtom> atoms) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlanningAtom atom : atoms) {
            Map<String, Object> row = new HashMap<>();
            row.put("paragraphId", atom.virtualParagraphId());
            row.put("originParagraphId", atom.originParagraphId());
            row.put("pageNumber", atom.pageNumber());
            row.put("sectionPath", atom.sectionPath());
            row.put("tokenCount", atom.tokenCount());
            row.put("text", atom.text());
            rows.add(row);
        }
        return JSON.toJSONString(rows);
    }

    /**
     * @Description: 校验输入参数与业务约束。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private List<SemanticSegment> validateAndRepairSegments(List<ParagraphAtom> atoms, List<SemanticSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }

        int minId = atoms.get(0).paragraphId();
        int maxId = atoms.get(atoms.size() - 1).paragraphId();
        List<SemanticSegment> sortedSegments = segments.stream()
                .filter(segment -> segment.confidence() >= MIN_CONFIDENCE)
                .filter(segment -> segment.startParagraphId() <= segment.endParagraphId())
                .filter(segment -> segment.startParagraphId() >= minId && segment.endParagraphId() <= maxId)
                .sorted(Comparator.comparingInt(SemanticSegment::startParagraphId))
                .toList();
        if (sortedSegments.isEmpty()) {
            return List.of();
        }

        List<SemanticSegment> repaired = new ArrayList<>();
        int cursor = minId;
        for (SemanticSegment segment : sortedSegments) {
            if (segment.endParagraphId() < cursor) {
                continue;
            }
            if (segment.startParagraphId() > cursor) {
                repaired.add(fallbackSegment(atoms, cursor, segment.startParagraphId() - 1));
            }
            SemanticSegment normalized = new SemanticSegment(
                    Math.max(segment.startParagraphId(), cursor),
                    segment.endParagraphId(),
                    segment.topic(),
                    segment.segmentType(),
                    segment.confidence()
            );
            repaired.addAll(splitOversizedSegment(atoms, normalized));
            cursor = segment.endParagraphId() + 1;
        }

        if (cursor <= maxId) {
            repaired.add(fallbackSegment(atoms, cursor, maxId));
        }
        return repaired;
    }

    /**
     * @Description: 按规则拆分输入内容。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private List<SemanticSegment> splitOversizedSegment(List<ParagraphAtom> atoms, SemanticSegment segment) {
        List<SemanticSegment> splitSegments = new ArrayList<>();
        int start = segment.startParagraphId();
        int currentTokens = 0;
        for (ParagraphAtom atom : atoms) {
            if (atom.paragraphId() < segment.startParagraphId() || atom.paragraphId() > segment.endParagraphId()) {
                continue;
            }
            if (currentTokens > 0 && currentTokens + atom.tokenCount() > MAX_SEGMENT_TOKENS) {
                splitSegments.add(new SemanticSegment(start, atom.paragraphId() - 1, segment.topic(), segment.segmentType(), segment.confidence()));
                start = atom.paragraphId();
                currentTokens = 0;
            }
            currentTokens += atom.tokenCount();
        }
        splitSegments.add(new SemanticSegment(start, segment.endParagraphId(), segment.topic(), segment.segmentType(), segment.confidence()));
        return splitSegments;
    }

    /**
     * @Description: 在主流程失败时提供兜底结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private SemanticSegment fallbackSegment(List<ParagraphAtom> atoms, int startParagraphId, int endParagraphId) {
        String topic = atoms.stream()
                .filter(atom -> atom.paragraphId() >= startParagraphId && atom.paragraphId() <= endParagraphId)
                .map(ParagraphAtom::sectionPath)
                .findFirst()
                .orElse("正文");
        return new SemanticSegment(startParagraphId, endParagraphId, topic, "OTHER", 1D);
    }

    private record PlanningAtom(
            int virtualParagraphId,
            int originParagraphId,
            int pageNumber,
            String sectionPath,
            String text,
            int tokenCount
    ) {
    }
}
