package com.example.aimilvusweb.service;

import com.alibaba.fastjson2.JSON;
import com.example.aimilvusweb.common.ocr.OcrClient;
import com.example.aimilvusweb.common.ocr.OcrRecognizedDocument;
import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ParagraphAtom;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ReportOcrParseService {

    private final OcrClient ocrClient;

    public ReportOcrParseService(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    public String parse(MultipartFile file) {
        return parseDetailed(file).cleanedText();
    }

    public ReportOcrParseResult parseDetailed(MultipartFile file) {
        if (!ocrClient.isConfigured()) {
            throw new IllegalStateException("OCR is required for report text extraction. Configure OCR_ENDPOINT or app.ocr.endpoint.");
        }
        OcrRecognizedDocument document = ocrClient.recognize(file);
        ReportOcrParseResult result = normalizeDocument(document);
        if (result.cleanedText().isBlank()) {
            throw new IllegalArgumentException("OCR recognized no usable report text");
        }
        return result;
    }

    private ReportOcrParseResult normalizeDocument(OcrRecognizedDocument document) {
        if (!document.pages().isEmpty()) {
            List<OcrPageResult> pages = new ArrayList<>();
            List<String> rawPageTexts = new ArrayList<>();
            List<String> cleanedPageTexts = new ArrayList<>();
            for (OcrRecognizedDocument.OcrPage page : document.pages()) {
                NormalizeResult normalized = normalizeOcrTextWithDiagnostics(page.text());
                rawPageTexts.add(page.text() == null ? "" : page.text());
                if (!normalized.text().isBlank()) {
                    cleanedPageTexts.add("[Page " + page.pageNumber() + "]\n\n" + normalized.text());
                }
                pages.add(new OcrPageResult(
                        page.pageNumber(),
                        page.text() == null ? "" : page.text(),
                        normalized.text(),
                        mergeDiagnostics(page.diagnostics(), normalized.diagnostics())
                ));
            }
            String cleanedText = String.join("\n\n", cleanedPageTexts);
            return new ReportOcrParseResult(String.join("\n\n", rawPageTexts), cleanedText, pages, atomizePages(pages));
        }
        NormalizeResult normalized = normalizeOcrTextWithDiagnostics(document.fullText());
        OcrPageResult page = new OcrPageResult(0, document.fullText() == null ? "" : document.fullText(), normalized.text(), normalized.diagnostics());
        return new ReportOcrParseResult(document.fullText() == null ? "" : document.fullText(), normalized.text(), List.of(page), atomizePages(List.of(page)));
    }

    String normalizeOcrText(String rawText) {
        return normalizeOcrTextWithDiagnostics(rawText).text();
    }

    NormalizeResult normalizeOcrTextWithDiagnostics(String rawText) {
        String normalized = rawText == null ? "" : rawText.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (normalized.isBlank()) {
            return new NormalizeResult("", JSON.toJSONString(Map.of("status", "blank")));
        }

        List<String> paragraphs = new ArrayList<>();
        List<String> removedNoiseLines = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String value = line.trim().replaceAll("[ \\t]+", " ");
            if (value.isBlank()) {
                flushParagraph(paragraphs, removedNoiseLines, paragraph);
                continue;
            }
            if (isPageMarker(value) || isLikelyHeading(value)) {
                flushParagraph(paragraphs, removedNoiseLines, paragraph);
                paragraphs.add(value);
                continue;
            }
            if (paragraph.isEmpty()) {
                paragraph.append(value);
                continue;
            }
            if (endsSentence(paragraph) || startsNewSemanticLine(value)) {
                flushParagraph(paragraphs, removedNoiseLines, paragraph);
                paragraph.append(value);
            } else {
                appendWrappedLine(paragraph, value);
            }
        }
        flushParagraph(paragraphs, removedNoiseLines, paragraph);
        String diagnostics = JSON.toJSONString(Map.of(
                "removedNoiseLineCount", removedNoiseLines.size(),
                "removedNoiseLines", removedNoiseLines
        ));
        return new NormalizeResult(String.join("\n\n", paragraphs), diagnostics);
    }

    private void flushParagraph(List<String> paragraphs, List<String> removedNoiseLines, StringBuilder paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        String value = paragraph.toString().trim();
        if (!value.isBlank() && isLikelyNoiseLine(value)) {
            removedNoiseLines.add(value);
        } else if (!value.isBlank()) {
            paragraphs.add(value);
        }
        paragraph.setLength(0);
    }

    private List<ParagraphAtom> atomizePages(List<OcrPageResult> pages) {
        List<ParagraphAtom> atoms = new ArrayList<>();
        String sectionPath = "正文";
        int paragraphId = 1;
        for (OcrPageResult page : pages) {
            for (String part : page.cleanedText().split("\\n\\s*\\n")) {
                String paragraph = part.trim();
                if (paragraph.isBlank() || isPageMarker(paragraph)) {
                    continue;
                }
                if (isLikelyHeading(paragraph)) {
                    sectionPath = paragraph;
                    continue;
                }
                atoms.add(new ParagraphAtom(
                        paragraphId,
                        page.pageNumber(),
                        sectionPath,
                        paragraph,
                        SemanticChunkUtils.estimateTokens(paragraph),
                        page.diagnostics()
                ));
                paragraphId++;
            }
        }
        return List.copyOf(atoms);
    }

    private String mergeDiagnostics(String externalDiagnostics, String normalizeDiagnostics) {
        if (externalDiagnostics == null || externalDiagnostics.isBlank()) {
            return normalizeDiagnostics;
        }
        if (normalizeDiagnostics == null || normalizeDiagnostics.isBlank()) {
            return externalDiagnostics;
        }
        return JSON.toJSONString(Map.of(
                "ocr", externalDiagnostics,
                "normalization", normalizeDiagnostics
        ));
    }

    private boolean isPageMarker(String line) {
        return line.matches("^\\[Page\\s+\\d+\\]$") || line.matches("^第\\s*\\d+\\s*页$");
    }

    private boolean isLikelyHeading(String line) {
        String value = line.trim();
        if (value.length() > 48 || value.matches(".*[。；，,.!?].*") || startsNewSemanticLine(value)) {
            return false;
        }
        return value.matches("^([一二三四五六七八九十]+[、.]|\\d+(\\.\\d+)*[、.)]?)\\s*\\S+.*")
                || value.matches(".*(摘要|要点|观点|评级|行业|公司|财务|盈利|估值|风险|提示|结论|投资|供给|需求|库存|价格|成本|政策).*");
    }

    private boolean endsSentence(StringBuilder paragraph) {
        if (paragraph.isEmpty()) {
            return false;
        }
        char last = paragraph.charAt(paragraph.length() - 1);
        return last == '。' || last == '！' || last == '？' || last == '；'
                || last == '.' || last == '!' || last == '?' || last == ';';
    }

    private boolean startsNewSemanticLine(String line) {
        return line.matches("^(首先|其次|再次|最后|一方面|另一方面|从供给|从需求|盈利预测|估值|风险|投资建议|我们认为).*");
    }

    private void appendWrappedLine(StringBuilder paragraph, String line) {
        if (paragraph.toString().endsWith("-")) {
            paragraph.deleteCharAt(paragraph.length() - 1);
            paragraph.append(line);
            return;
        }
        if (needsSpace(paragraph.charAt(paragraph.length() - 1), line.charAt(0))) {
            paragraph.append(' ');
        }
        paragraph.append(line);
    }

    private boolean needsSpace(char left, char right) {
        return isAsciiLetterOrDigit(left) && isAsciiLetterOrDigit(right);
    }

    private boolean isAsciiLetterOrDigit(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    private boolean isLikelyNoiseLine(String line) {
        String value = line.replaceAll("\\s+", "");
        if (value.length() < 8) {
            return false;
        }
        int digits = 0;
        int symbols = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isDigit(ch)) {
                digits++;
            } else if (!Character.isLetter(ch)) {
                symbols++;
            }
        }
        double noiseRatio = (digits + symbols) / (double) value.length();
        return noiseRatio > 0.75D;
    }

    record NormalizeResult(String text, String diagnostics) {
    }

    public record OcrPageResult(
            int pageNumber,
            String rawText,
            String cleanedText,
            String diagnostics
    ) {
    }

    public record ReportOcrParseResult(
            String rawText,
            String cleanedText,
            List<OcrPageResult> pages,
            List<ParagraphAtom> atoms
    ) {
        public ReportOcrParseResult {
            pages = pages == null ? List.of() : List.copyOf(pages);
            atoms = atoms == null ? List.of() : List.copyOf(atoms);
        }
    }
}
