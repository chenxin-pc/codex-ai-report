package com.example.aimilvusweb.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class SemanticChunkUtils {

    private static final Pattern NUMBERED_HEADING = Pattern.compile("^([一二三四五六七八九十]+[、.]|\\d+(\\.\\d+)*[、.)]?)\\s*\\S+");
    private static final Pattern REPORT_HEADING_KEYWORD = Pattern.compile(".*(摘要|要点|观点|评级|行业|公司|财务|盈利|估值|风险|提示|结论|投资|供给|需求|库存|价格|成本|政策).*");
    private static final ChunkingOptions DEFAULT_REPORT_OPTIONS = new ChunkingOptions(1200, 1800, 3500, 6000, 200);

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
            int tokenCount
    ) {
    }

    public record ParagraphAtom(
            int paragraphId,
            String sectionPath,
            String text,
            int tokenCount
    ) {
    }

    public record SemanticSegment(
            int startParagraphId,
            int endParagraphId,
            String topic,
            String segmentType,
            double confidence
    ) {
    }

    public static ReportSemanticChunks chunkReport(String text) {
        return chunkReport(text, DEFAULT_REPORT_OPTIONS);
    }

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
            parents.add(new ReportChunkSlice("PARENT", parentIndex, 0, parentDraft.sectionPath(), parentText, estimateTokens(parentText)));
            children.addAll(buildChildSlices(parentDraft, parentIndex, options));
        }

        return new ReportSemanticChunks(parents, children);
    }

    public static List<ParagraphAtom> atomizeReportParagraphs(String text) {
        List<SectionParagraph> paragraphs = parseSectionParagraphs(text);
        List<ParagraphAtom> atoms = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            SectionParagraph paragraph = paragraphs.get(i);
            atoms.add(new ParagraphAtom(i + 1, paragraph.sectionPath(), paragraph.text(), estimateTokens(paragraph.text())));
        }
        return atoms;
    }

    public static ReportSemanticChunks chunkReportBySegments(List<ParagraphAtom> atoms, List<SemanticSegment> segments) {
        return chunkReportBySegments(atoms, segments, DEFAULT_REPORT_OPTIONS);
    }

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
                parentDrafts.add(new ParentDraft(sectionPath, List.copyOf(paragraphs)));
            }
        }

        List<ReportChunkSlice> parents = new ArrayList<>();
        List<ReportChunkSlice> children = new ArrayList<>();
        for (int parentIndex = 0; parentIndex < parentDrafts.size(); parentIndex++) {
            ParentDraft parentDraft = parentDrafts.get(parentIndex);
            String parentText = formatChunkText(parentDraft.sectionPath(), parentDraft.text());
            parents.add(new ReportChunkSlice("PARENT", parentIndex, 0, parentDraft.sectionPath(), parentText, estimateTokens(parentText)));
            children.addAll(buildChildSlices(parentDraft, parentIndex, options));
        }
        return new ReportSemanticChunks(parents, children);
    }

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

    private static List<SectionParagraph> parseSectionParagraphs(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n").trim();
        String[] parts = normalized.split("\\n\\s*\\n");
        List<SectionParagraph> paragraphs = new ArrayList<>();
        String sectionPath = "正文";

        for (String part : parts) {
            String paragraph = part.trim().replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n");
            if (paragraph.isBlank()) {
                continue;
            }
            if (isHeading(paragraph)) {
                sectionPath = cleanHeading(paragraph);
                continue;
            }
            paragraphs.add(new SectionParagraph(sectionPath, paragraph));
        }
        return paragraphs;
    }

    private static boolean isHeading(String paragraph) {
        String value = paragraph.trim();
        if (value.length() > 60 || value.contains("。") || value.contains("；")) {
            return false;
        }
        return NUMBERED_HEADING.matcher(value).matches() || REPORT_HEADING_KEYWORD.matcher(value).matches();
    }

    private static String cleanHeading(String heading) {
        return heading.replaceAll("\\s+", " ").trim();
    }

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
                parents.add(new ParentDraft(currentSection, List.copyOf(buffer)));
                buffer.clear();
                currentTokens = 0;
            }
            currentSection = paragraph.sectionPath();
            buffer.add(paragraph.text());
            currentTokens += paragraphTokens;
        }

        if (!buffer.isEmpty()) {
            parents.add(new ParentDraft(currentSection, List.copyOf(buffer)));
        }
        return parents;
    }

    private static List<ReportChunkSlice> buildChildSlices(ParentDraft parentDraft, int parentIndex, ChunkingOptions options) {
        List<ReportChunkSlice> slices = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        String previousText = "";
        int currentTokens = 0;

        for (String paragraph : parentDraft.paragraphs()) {
            int paragraphTokens = estimateTokens(paragraph);
            boolean exceedsHardMax = currentTokens > 0 && currentTokens + paragraphTokens > options.childMaxTokens();
            boolean reachedTargetAtSemanticBoundary = currentTokens >= options.childTargetTokens() && startsNewAnalyticalUnit(paragraph);
            if ((exceedsHardMax || reachedTargetAtSemanticBoundary) && !buffer.isEmpty()) {
                previousText = addChildSlice(slices, parentDraft.sectionPath(), buffer, previousText, parentIndex, options);
                buffer.clear();
                currentTokens = 0;
            }
            buffer.add(paragraph);
            currentTokens += paragraphTokens;
        }

        if (!buffer.isEmpty()) {
            addChildSlice(slices, parentDraft.sectionPath(), buffer, previousText, parentIndex, options);
        }
        return slices;
    }

    private static String addChildSlice(List<ReportChunkSlice> slices,
                                        String sectionPath,
                                        List<String> paragraphs,
                                        String previousText,
                                        int parentIndex,
                                        ChunkingOptions options) {
        String text = String.join("\n\n", paragraphs);
        String overlap = tailByTokens(previousText, options.overlapTokens());
        String chunkText = formatChunkText(sectionPath, overlap.isBlank() ? text : overlap + "\n\n" + text);
        slices.add(new ReportChunkSlice("CHILD", parentIndex, slices.size(), sectionPath, chunkText, estimateTokens(chunkText)));
        return text;
    }

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

    private static String formatChunkText(String sectionPath, String text) {
        return "Section: " + sectionPath + "\n\n" + text.trim();
    }

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

    private record SectionParagraph(String sectionPath, String text) {
    }

    private record ParentDraft(String sectionPath, List<String> paragraphs) {
        String text() {
            return String.join("\n\n", paragraphs);
        }
    }
}
