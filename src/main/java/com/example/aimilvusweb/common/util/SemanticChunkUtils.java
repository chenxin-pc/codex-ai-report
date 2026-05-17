package com.example.aimilvusweb.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @Description: SemanticChunkUtils类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public final class SemanticChunkUtils {

    private static final Pattern NUMBERED_HEADING = Pattern.compile("^([一二三四五六七八九十]+[、.]|\\d+(\\.\\d+)*[、.)]?)\\s*\\S+");
    private static final Pattern REPORT_HEADING_KEYWORD = Pattern.compile(".*(摘要|要点|观点|评级|行业|公司|财务|盈利|估值|风险|提示|结论|投资|供给|需求|库存|价格|成本|政策).*");
    private static final ChunkingOptions DEFAULT_REPORT_OPTIONS = new ChunkingOptions(1200, 1800, 3500, 6000, 200);

    /**
     * @Description: 初始化SemanticChunkUtils依赖与运行所需组件。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private SemanticChunkUtils() {
    }

    public record ChunkingOptions(
            int childTargetTokens,
            int childMaxTokens,
            int parentTargetTokens,
            int parentMaxTokens,
            int overlapTokens
    ) {
    }

    public record ReportSemanticChunks(
            List<ReportChunkSlice> parents,
            List<ReportChunkSlice> children
    ) {
        /**
         * @Description: 判断是否满足Empty条件。
         * @author: cx
         * @Date: 2026-05-17 10:24:01
         */
        public boolean isEmpty() {
            return parents.isEmpty() || children.isEmpty();
        }
    }

    public record ReportChunkSlice(
            String chunkType,
            int parentIndex,
            int chunkIndexInParent,
            String sectionPath,
            String text,
            int tokenCount,
            int startParagraphId,
            int endParagraphId,
            int startPageNumber,
            int endPageNumber,
            String segmentType
    ) {
        /**
         * @Description: 初始化ReportChunkSlice依赖与运行所需组件。
         * @author: cx
         * @Date: 2026-05-17 10:24:01
         */
        public ReportChunkSlice(String chunkType,
                                int parentIndex,
                                int chunkIndexInParent,
                                String sectionPath,
                                String text,
                                int tokenCount) {
            this(chunkType, parentIndex, chunkIndexInParent, sectionPath, text, tokenCount, 0, 0, 0, 0, "OTHER");
        }
    }

    public record ParagraphAtom(
            int paragraphId,
            int pageNumber,
            String sectionPath,
            String text,
            int tokenCount,
            String diagnostics
    ) {
        /**
         * @Description: 初始化ParagraphAtom依赖与运行所需组件。
         * @author: cx
         * @Date: 2026-05-17 10:24:01
         */
        public ParagraphAtom(int paragraphId, String sectionPath, String text, int tokenCount) {
            this(paragraphId, 0, sectionPath, text, tokenCount, "");
        }
    }

    public record SemanticSegment(
            int startParagraphId,
            int endParagraphId,
            String topic,
            String segmentType,
            double confidence
    ) {
    }

    /**
     * @Description: 执行文本切片并返回切片结果。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public static ReportSemanticChunks chunkReport(String text) {
        return chunkReport(text, DEFAULT_REPORT_OPTIONS);
    }

    /**
     * @Description: 执行文本切片并返回切片结果。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public static ReportSemanticChunks chunkReport(String text, ChunkingOptions options) {
        List<SectionParagraph> paragraphs = parseSectionParagraphs(text);
        if (paragraphs.isEmpty()) {
            return new ReportSemanticChunks(List.of(), List.of());
        }

        List<ParentDraft> parentDrafts = buildParentDrafts(paragraphs, options);
        List<ReportChunkSlice> parents = new ArrayList<>();
        List<ReportChunkSlice> children = new ArrayList<>();

        for (int parentIndex = 0; parentIndex < parentDrafts.size(); parentIndex++) {
            ParentDraft parentDraft = parentDrafts.get(parentIndex);
            String parentText = formatChunkText(parentDraft.sectionPath(), parentDraft.text());
            parents.add(new ReportChunkSlice("PARENT", parentIndex, 0, parentDraft.sectionPath(), parentText, estimateTokens(parentText),
                    parentDraft.startParagraphId(), parentDraft.endParagraphId(), parentDraft.startPageNumber(), parentDraft.endPageNumber(), parentDraft.segmentType()));
            children.addAll(buildChildSlices(parentDraft, parentIndex, options));
        }

        return new ReportSemanticChunks(parents, children);
    }

    /**
     * @Description: 将文本拆分为最小语义单元。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public static List<ParagraphAtom> atomizeReportParagraphs(String text) {
        List<SectionParagraph> paragraphs = parseSectionParagraphs(text);
        List<ParagraphAtom> atoms = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            SectionParagraph paragraph = paragraphs.get(i);
            atoms.add(new ParagraphAtom(i + 1, paragraph.pageNumber(), paragraph.sectionPath(), paragraph.text(), estimateTokens(paragraph.text()), ""));
        }
        return atoms;
    }

    /**
     * @Description: 执行文本切片并返回切片结果。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public static ReportSemanticChunks chunkReportBySegments(List<ParagraphAtom> atoms, List<SemanticSegment> segments) {
        return chunkReportBySegments(atoms, segments, DEFAULT_REPORT_OPTIONS);
    }

    /**
     * @Description: 执行文本切片并返回切片结果。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public static ReportSemanticChunks chunkReportBySegments(List<ParagraphAtom> atoms,
                                                             List<SemanticSegment> segments,
                                                             ChunkingOptions options) {
        if (atoms == null || atoms.isEmpty() || segments == null || segments.isEmpty()) {
            return new ReportSemanticChunks(List.of(), List.of());
        }

        List<ParentDraft> parentDrafts = new ArrayList<>();
        for (SemanticSegment segment : segments) {
            List<String> paragraphs = new ArrayList<>();
            String sectionPath = resolveSegmentSectionPath(atoms, segment);
            for (ParagraphAtom atom : atoms) {
                if (atom.paragraphId() >= segment.startParagraphId() && atom.paragraphId() <= segment.endParagraphId()) {
                    paragraphs.add(atom.text());
                }
            }
            if (!paragraphs.isEmpty()) {
                parentDrafts.add(new ParentDraft(
                        sectionPath,
                        List.copyOf(paragraphs),
                        segment.startParagraphId(),
                        segment.endParagraphId(),
                        resolveSegmentStartPage(atoms, segment),
                        resolveSegmentEndPage(atoms, segment),
                        normalizeSegmentType(segment.segmentType())
                ));
            }
        }

        List<ReportChunkSlice> parents = new ArrayList<>();
        List<ReportChunkSlice> children = new ArrayList<>();
        for (int parentIndex = 0; parentIndex < parentDrafts.size(); parentIndex++) {
            ParentDraft parentDraft = parentDrafts.get(parentIndex);
            String parentText = formatChunkText(parentDraft.sectionPath(), parentDraft.text());
            parents.add(new ReportChunkSlice("PARENT", parentIndex, 0, parentDraft.sectionPath(), parentText, estimateTokens(parentText),
                    parentDraft.startParagraphId(), parentDraft.endParagraphId(), parentDraft.startPageNumber(), parentDraft.endPageNumber(), parentDraft.segmentType()));
            children.addAll(buildChildSlices(parentDraft, parentIndex, options));
        }
        return new ReportSemanticChunks(parents, children);
    }

    /**
     * @Description: 执行文本切片并返回切片结果。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public static List<String> chunkByParagraphWindow(String text, int paragraphWindow, int overlapParagraphs) {
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n").trim();
        String[] parts = normalized.split("\\n\\s*\\n");

        List<String> paragraphs = new ArrayList<>();
        for (String part : parts) {
            String paragraph = part.trim().replaceAll("\\s+", " ");
            if (!paragraph.isBlank()) {
                paragraphs.add(paragraph);
            }
        }

        if (paragraphs.isEmpty()) {
            return List.of();
        }

        int window = Math.max(paragraphWindow, 1);
        int overlap = Math.min(Math.max(overlapParagraphs, 0), window - 1);
        int step = Math.max(1, window - overlap);

        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i += step) {
            int end = Math.min(paragraphs.size(), i + window);
            String chunk = String.join("\n\n", paragraphs.subList(i, end));
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end == paragraphs.size()) {
                break;
            }
        }

        return chunks;
    }

    /**
     * @Description: 执行estimateTokens相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjkChars = 0;
        int asciiChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                cjkChars++;
            } else if (!Character.isWhitespace(ch)) {
                asciiChars++;
            }
        }
        return Math.max(1, (int) Math.ceil(cjkChars / 1.5D + asciiChars / 4.0D));
    }

    /**
     * @Description: 解析输入内容并输出结构化结果。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static List<SectionParagraph> parseSectionParagraphs(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n").trim();
        String[] parts = normalized.split("\\n\\s*\\n");
        List<SectionParagraph> paragraphs = new ArrayList<>();
        String sectionPath = "正文";
        int pageNumber = 0;

        for (String part : parts) {
            String paragraph = part.trim().replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n");
            if (paragraph.isBlank()) {
                continue;
            }
            int markerPageNumber = resolvePageMarker(paragraph);
            if (markerPageNumber > 0) {
                pageNumber = markerPageNumber;
                continue;
            }
            if (isHeading(paragraph)) {
                sectionPath = cleanHeading(paragraph);
                continue;
            }
            paragraphs.add(new SectionParagraph(sectionPath, paragraph, pageNumber));
        }
        return paragraphs;
    }

    /**
     * @Description: 根据上下文解析并确定最终值。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static int resolvePageMarker(String paragraph) {
        String value = paragraph.trim();
        if (!value.matches("^\\[Page\\s+\\d+\\]$")) {
            return 0;
        }
        String digits = value.replaceAll("\\D+", "");
        if (digits.isBlank()) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    /**
     * @Description: 判断是否满足Heading条件。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static boolean isHeading(String paragraph) {
        String value = paragraph.trim();
        if (value.length() > 60 || value.contains("。") || value.contains("；")) {
            return false;
        }
        return NUMBERED_HEADING.matcher(value).matches() || REPORT_HEADING_KEYWORD.matcher(value).matches();
    }

    /**
     * @Description: 执行cleanHeading相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static String cleanHeading(String heading) {
        return heading.replaceAll("\\s+", " ").trim();
    }

    /**
     * @Description: 构建目标对象或请求数据。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static List<ParentDraft> buildParentDrafts(List<SectionParagraph> paragraphs, ChunkingOptions options) {
        List<ParentDraft> parents = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        String currentSection = paragraphs.get(0).sectionPath();
        int currentTokens = 0;

        for (SectionParagraph paragraph : paragraphs) {
            int paragraphTokens = estimateTokens(paragraph.text());
            boolean sectionChanged = !paragraph.sectionPath().equals(currentSection);
            boolean exceedsHardMax = currentTokens > 0 && currentTokens + paragraphTokens > options.parentMaxTokens();
            boolean reachedTargetAtSemanticBoundary = currentTokens >= options.parentTargetTokens() && startsNewAnalyticalUnit(paragraph.text());
            if ((exceedsHardMax || sectionChanged || reachedTargetAtSemanticBoundary) && !buffer.isEmpty()) {
                parents.add(new ParentDraft(currentSection, List.copyOf(buffer), 0, 0, 0, 0, "OTHER"));
                buffer.clear();
                currentTokens = 0;
            }
            currentSection = paragraph.sectionPath();
            buffer.add(paragraph.text());
            currentTokens += paragraphTokens;
        }

        if (!buffer.isEmpty()) {
            parents.add(new ParentDraft(currentSection, List.copyOf(buffer), 0, 0, 0, 0, "OTHER"));
        }
        return parents;
    }

    /**
     * @Description: 构建目标对象或请求数据。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static List<ReportChunkSlice> buildChildSlices(ParentDraft parentDraft, int parentIndex, ChunkingOptions options) {
        List<ReportChunkSlice> slices = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        String previousText = "";
        int currentTokens = 0;

        int sectionPrefixTokens = estimateTokens(formatChunkText(parentDraft.sectionPath(), ""));
        int bodyTokenBudget = Math.max(1, options.childMaxTokens() - sectionPrefixTokens);
        for (String paragraph : parentDraft.paragraphs()) {
            List<String> normalizedParagraphs = splitOversizedParagraph(paragraph, bodyTokenBudget);
            for (String normalizedParagraph : normalizedParagraphs) {
                int paragraphTokens = estimateTokens(normalizedParagraph);
                boolean exceedsHardMax = currentTokens > 0 && currentTokens + paragraphTokens > options.childMaxTokens();
                boolean reachedTargetAtSemanticBoundary = currentTokens >= options.childTargetTokens() && startsNewAnalyticalUnit(normalizedParagraph);
                if ((exceedsHardMax || reachedTargetAtSemanticBoundary) && !buffer.isEmpty()) {
                    previousText = addChildSlice(slices, parentDraft, buffer, previousText, parentIndex, options);
                    buffer.clear();
                    currentTokens = 0;
                }
                buffer.add(normalizedParagraph);
                currentTokens += paragraphTokens;
            }
        }

        if (!buffer.isEmpty()) {
            addChildSlice(slices, parentDraft, buffer, previousText, parentIndex, options);
        }
        return normalizeChildSliceSize(slices, options);
    }

    /**
     * @Description: 向目标集合追加处理结果。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static String addChildSlice(List<ReportChunkSlice> slices,
                                        ParentDraft parentDraft,
                                        List<String> paragraphs,
                                        String previousText,
                                        int parentIndex,
                                        ChunkingOptions options) {
        String text = String.join("\n\n", paragraphs);
        String overlap = tailByTokens(previousText, options.overlapTokens());
        String chunkText = formatChunkText(parentDraft.sectionPath(), overlap.isBlank() ? text : overlap + "\n\n" + text);
        slices.add(new ReportChunkSlice("CHILD", parentIndex, slices.size(), parentDraft.sectionPath(), chunkText, estimateTokens(chunkText),
                parentDraft.startParagraphId(), parentDraft.endParagraphId(), parentDraft.startPageNumber(), parentDraft.endPageNumber(), parentDraft.segmentType()));
        return text;
    }

    /**
     * @Description: 执行startsNewAnalyticalUnit相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static boolean startsNewAnalyticalUnit(String paragraph) {
        String value = paragraph.trim();
        return value.startsWith("从供给")
                || value.startsWith("从需求")
                || value.startsWith("盈利预测")
                || value.startsWith("估值")
                || value.startsWith("风险")
                || value.startsWith("投资建议")
                || value.startsWith("我们认为")
                || value.matches("^(首先|其次|再次|最后|一方面|另一方面)[，,].*");
    }

    /**
     * @Description: 执行tailByTokens相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static String tailByTokens(String text, int maxTokens) {
        if (text == null || text.isBlank() || maxTokens <= 0) {
            return "";
        }
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<String> selected = new ArrayList<>();
        int tokens = 0;
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            String paragraph = paragraphs[i].trim();
            int paragraphTokens = estimateTokens(paragraph);
            if (!selected.isEmpty() && tokens + paragraphTokens > maxTokens) {
                break;
            }
            selected.add(0, paragraph);
            tokens += paragraphTokens;
            if (tokens >= maxTokens) {
                break;
            }
        }
        return String.join("\n\n", selected);
    }

    /**
     * @Description: 执行formatChunkText相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static String formatChunkText(String sectionPath, String text) {
        return "Section: " + sectionPath + "\n\n" + text.trim();
    }

    /**
     * @Description: 按规则拆分输入内容。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static List<String> splitOversizedParagraph(String paragraph, int childMaxTokens) {
        if (estimateTokens(paragraph) <= childMaxTokens) {
            return List.of(paragraph);
        }
        List<String> units = splitToSentenceLikeUnits(paragraph);
        List<String> normalized = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;

        for (String unit : units) {
            int unitTokens = estimateTokens(unit);
            if (unitTokens > childMaxTokens) {
                if (!buffer.isEmpty()) {
                    normalized.add(buffer.toString().trim());
                    buffer.setLength(0);
                    bufferTokens = 0;
                }
                normalized.addAll(hardSplitByTokenBudget(unit, childMaxTokens));
                continue;
            }
            if (bufferTokens > 0 && bufferTokens + unitTokens > childMaxTokens) {
                normalized.add(buffer.toString().trim());
                buffer.setLength(0);
                bufferTokens = 0;
            }
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(unit.trim());
            bufferTokens += unitTokens;
        }

        if (!buffer.isEmpty()) {
            normalized.add(buffer.toString().trim());
        }
        if (normalized.isEmpty()) {
            return hardSplitByTokenBudget(paragraph, childMaxTokens);
        }
        return normalized;
    }

    /**
     * @Description: 按规则拆分输入内容。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static List<String> splitToSentenceLikeUnits(String text) {
        String[] coarse = text.split("(?<=[。！？；.!?;])\\s+|\\n+");
        List<String> units = new ArrayList<>();
        for (String item : coarse) {
            String normalized = item == null ? "" : item.trim();
            if (!normalized.isBlank()) {
                units.add(normalized);
            }
        }
        if (!units.isEmpty()) {
            return units;
        }
        List<String> fallback = new ArrayList<>();
        for (String part : text.split("\\s+")) {
            if (!part.isBlank()) {
                fallback.add(part.trim());
            }
        }
        return fallback;
    }

    /**
     * @Description: 对输入数据进行规范化处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static List<ReportChunkSlice> normalizeChildSliceSize(List<ReportChunkSlice> slices, ChunkingOptions options) {
        List<ReportChunkSlice> normalized = new ArrayList<>();
        for (ReportChunkSlice slice : slices) {
            if (slice.tokenCount() <= options.childMaxTokens()) {
                normalized.add(new ReportChunkSlice(
                        slice.chunkType(),
                        slice.parentIndex(),
                        normalized.size(),
                        slice.sectionPath(),
                        slice.text(),
                        slice.tokenCount(),
                        slice.startParagraphId(),
                        slice.endParagraphId(),
                        slice.startPageNumber(),
                        slice.endPageNumber(),
                        slice.segmentType()
                ));
                continue;
            }
            String body = stripSectionPrefix(slice.text());
            int sectionPrefixTokens = estimateTokens(formatChunkText(slice.sectionPath(), ""));
            int bodyTokenBudget = Math.max(1, options.childMaxTokens() - sectionPrefixTokens);
            List<String> parts = splitOversizedParagraph(body, bodyTokenBudget);
            for (String part : parts) {
                String text = formatChunkText(slice.sectionPath(), part);
                normalized.add(new ReportChunkSlice(
                        "CHILD",
                        slice.parentIndex(),
                        normalized.size(),
                        slice.sectionPath(),
                        text,
                        estimateTokens(text),
                        slice.startParagraphId(),
                        slice.endParagraphId(),
                        slice.startPageNumber(),
                        slice.endPageNumber(),
                        slice.segmentType()
                ));
            }
        }
        return normalized;
    }

    /**
     * @Description: 执行hardSplitByTokenBudget相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static List<String> hardSplitByTokenBudget(String text, int tokenBudget) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return parts;
        }
        String normalized = text.trim();
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            buffer.append(normalized.charAt(i));
            if (estimateTokens(buffer.toString()) > tokenBudget) {
                int lastIndex = buffer.length() - 1;
                String prefix = buffer.substring(0, lastIndex).trim();
                if (!prefix.isBlank()) {
                    parts.add(prefix);
                }
                buffer.setLength(0);
                buffer.append(normalized.charAt(i));
            }
        }
        String tail = buffer.toString().trim();
        if (!tail.isBlank()) {
            parts.add(tail);
        }
        return parts;
    }

    /**
     * @Description: 执行stripSectionPrefix相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static String stripSectionPrefix(String text) {
        int separatorIndex = text.indexOf("\n\n");
        if (separatorIndex < 0) {
            return text;
        }
        String prefix = text.substring(0, separatorIndex).trim();
        if (prefix.startsWith("Section:")) {
            return text.substring(separatorIndex + 2).trim();
        }
        return text;
    }

    /**
     * @Description: 根据上下文解析并确定最终值。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static String resolveSegmentSectionPath(List<ParagraphAtom> atoms, SemanticSegment segment) {
        String topic = segment.topic() == null ? "" : segment.topic().trim();
        if (!topic.isBlank()) {
            return topic;
        }
        return atoms.stream()
                .filter(atom -> atom.paragraphId() >= segment.startParagraphId() && atom.paragraphId() <= segment.endParagraphId())
                .map(ParagraphAtom::sectionPath)
                .findFirst()
                .orElse("正文");
    }

    /**
     * @Description: 根据上下文解析并确定最终值。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static int resolveSegmentStartPage(List<ParagraphAtom> atoms, SemanticSegment segment) {
        return atoms.stream()
                .filter(atom -> atom.paragraphId() >= segment.startParagraphId() && atom.paragraphId() <= segment.endParagraphId())
                .mapToInt(ParagraphAtom::pageNumber)
                .filter(pageNumber -> pageNumber > 0)
                .min()
                .orElse(0);
    }

    /**
     * @Description: 根据上下文解析并确定最终值。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static int resolveSegmentEndPage(List<ParagraphAtom> atoms, SemanticSegment segment) {
        return atoms.stream()
                .filter(atom -> atom.paragraphId() >= segment.startParagraphId() && atom.paragraphId() <= segment.endParagraphId())
                .mapToInt(ParagraphAtom::pageNumber)
                .filter(pageNumber -> pageNumber > 0)
                .max()
                .orElse(0);
    }

    /**
     * @Description: 对输入数据进行规范化处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static String normalizeSegmentType(String segmentType) {
        if (segmentType == null || segmentType.isBlank()) {
            return "OTHER";
        }
        return segmentType.trim().toUpperCase();
    }

    private record SectionParagraph(String sectionPath, String text, int pageNumber) {
    }

    private record ParentDraft(String sectionPath,
                               List<String> paragraphs,
                               int startParagraphId,
                               int endParagraphId,
                               int startPageNumber,
                               int endPageNumber,
                               String segmentType) {
        String text() {
            return String.join("\n\n", paragraphs);
        }
    }
}
