package com.example.aimilvusweb.service;

import com.alibaba.fastjson2.JSON;
import com.example.aimilvusweb.common.llm.QwenClient;
import com.example.aimilvusweb.common.prompt.PromptTemplateService;
import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ParagraphAtom;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ReportSemanticChunks;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.SemanticSegment;
import com.example.aimilvusweb.dto.LlmChunkPlanRespDTO;
import com.example.aimilvusweb.dto.LlmChunkSegmentRespDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportSemanticChunkService {

    private static final Logger log = LoggerFactory.getLogger(ReportSemanticChunkService.class);
    private static final int LLM_BATCH_PARAGRAPHS = 60;
    private static final int MAX_SEGMENT_TOKENS = 6000;
    private static final double MIN_CONFIDENCE = 0.35D;

    private final QwenClient qwenClient;
    private final PromptTemplateService promptTemplateService;

    public ReportSemanticChunkService(QwenClient qwenClient, PromptTemplateService promptTemplateService) {
        this.qwenClient = qwenClient;
        this.promptTemplateService = promptTemplateService;
    }

    public ReportSemanticChunks chunk(String parsedText) {
        List<ParagraphAtom> atoms = SemanticChunkUtils.atomizeReportParagraphs(parsedText);
        if (atoms.isEmpty()) {
            return new ReportSemanticChunks(List.of(), List.of());
        }

        List<SemanticSegment> plannedSegments = planSemanticSegments(atoms);
        List<SemanticSegment> repairedSegments = validateAndRepairSegments(atoms, plannedSegments);
        if (repairedSegments.isEmpty()) {
            throw new IllegalStateException("LLM semantic chunk plan produced no valid segments");
        }

        ReportSemanticChunks chunks = SemanticChunkUtils.chunkReportBySegments(atoms, repairedSegments);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No chunks generated from LLM semantic segments");
        }
        return chunks;
    }

    private List<SemanticSegment> planSemanticSegments(List<ParagraphAtom> atoms) {
        List<SemanticSegment> segments = new ArrayList<>();
        for (int start = 0; start < atoms.size(); start += LLM_BATCH_PARAGRAPHS) {
            int end = Math.min(atoms.size(), start + LLM_BATCH_PARAGRAPHS);
            List<ParagraphAtom> batch = atoms.subList(start, end);
            LlmChunkPlanRespDTO plan = requestChunkPlan(batch);
            if (plan == null || plan.segments() == null || plan.segments().isEmpty()) {
                throw new IllegalStateException("LLM semantic chunk plan is empty for paragraph batch " + (start + 1) + "-" + end);
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

    private LlmChunkPlanRespDTO requestChunkPlan(List<ParagraphAtom> atoms) {
        try {
            String systemPrompt = promptTemplateService.loadTemplate("prompts/chunk-boundary-system-prompt.txt");
            String userPrompt = promptTemplateService.render("prompts/chunk-boundary-user-prompt.txt",
                    Map.of("paragraphs", toParagraphJson(atoms)));
            return qwenClient.chatForEntity(systemPrompt, userPrompt, LlmChunkPlanRespDTO.class);
        } catch (Exception e) {
            log.warn("LLM semantic chunk plan request failed", e);
            throw new IllegalStateException("LLM semantic chunk plan request failed: " + e.getMessage(), e);
        }
    }

    private String toParagraphJson(List<ParagraphAtom> atoms) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ParagraphAtom atom : atoms) {
            Map<String, Object> row = new HashMap<>();
            row.put("paragraphId", atom.paragraphId());
            row.put("sectionPath", atom.sectionPath());
            row.put("tokenCount", atom.tokenCount());
            row.put("text", atom.text());
            rows.add(row);
        }
        return JSON.toJSONString(rows);
    }

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

    private SemanticSegment fallbackSegment(List<ParagraphAtom> atoms, int startParagraphId, int endParagraphId) {
        String topic = atoms.stream()
                .filter(atom -> atom.paragraphId() >= startParagraphId && atom.paragraphId() <= endParagraphId)
                .map(ParagraphAtom::sectionPath)
                .findFirst()
                .orElse("正文");
        return new SemanticSegment(startParagraphId, endParagraphId, topic, "OTHER", 1D);
    }
}
