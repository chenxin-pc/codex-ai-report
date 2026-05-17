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
import java.util.List;
import java.util.Map;

@Service
/**
 * @Description: ReportSemanticChunkService类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class ReportSemanticChunkService {

    private static final Logger log = LoggerFactory.getLogger(ReportSemanticChunkService.class);
    private static final int LLM_BATCH_PARAGRAPHS = 60;
    private static final int MAX_SEGMENT_TOKENS = 6000;
    private static final double MIN_CONFIDENCE = 0.35D;

    private final QwenClient qwenClient;
    private final PromptTemplateService promptTemplateService;
    private final ReportQualityProperties reportQualityProperties;

    /**
     * @Description: 初始化ReportSemanticChunkService依赖与运行所需组件。
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
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public ReportSemanticChunks chunk(String parsedText) {
        List<ParagraphAtom> atoms = SemanticChunkUtils.atomizeReportParagraphs(parsedText);
        return chunk(atoms);
    }

    /**
     * @Description: 执行文本切片并返回切片结果。
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
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private List<SemanticSegment> planSemanticSegments(List<ParagraphAtom> atoms) {
        List<SemanticSegment> segments = new ArrayList<>();
        for (int start = 0; start < atoms.size(); start += LLM_BATCH_PARAGRAPHS) {
            int end = Math.min(atoms.size(), start + LLM_BATCH_PARAGRAPHS);
            List<ParagraphAtom> batch = atoms.subList(start, end);
            LlmChunkPlanRespDTO plan = requestChunkPlan(batch);
            if (plan == null) {
                throw new LlmSemanticChunkException("LLM semantic chunk failed: model returned null plan for paragraph batch " + (start + 1) + "-" + end);
            }
            if (plan.segments() == null || plan.segments().isEmpty()) {
                throw new LlmSemanticChunkException("LLM semantic chunk failed: model returned empty segments for paragraph batch " + (start + 1) + "-" + end);
            }
            for (LlmChunkSegmentRespDTO segment : plan.segments()) {
                if (segment.startParagraphId() != null && segment.endParagraphId() != null) {
                    segments.add(new SemanticSegment(
                            segment.startParagraphId(),
                            segment.endParagraphId(),
                            segment.topic(),
                            segment.segmentType(),
                            segment.confidence() == null ? 0D : segment.confidence()
                    ));
                }
            }
        }
        return segments;
    }

    /**
     * @Description: 向外部服务发送请求并处理响应。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private LlmChunkPlanRespDTO requestChunkPlan(List<ParagraphAtom> atoms) {
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
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String toParagraphJson(List<ParagraphAtom> atoms) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ParagraphAtom atom : atoms) {
            Map<String, Object> row = new HashMap<>();
            row.put("paragraphId", atom.paragraphId());
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
}
