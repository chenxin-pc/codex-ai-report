package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.ocr.OcrClient;
import com.example.aimilvusweb.common.ocr.OcrRecognizedDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Service
public class ReportOcrParseService {

    private final OcrClient ocrClient;

    public ReportOcrParseService(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    public String parse(MultipartFile file) {
        if (!ocrClient.isConfigured()) {
            throw new IllegalStateException("OCR is required for report text extraction. Configure OCR_ENDPOINT or app.ocr.endpoint.");
        }
        String rawText = extractByOcr(file);
        String normalized = normalizeOcrText(rawText);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("OCR recognized no usable report text");
        }
        return normalized;
    }

    private String extractByOcr(MultipartFile file) {
        OcrRecognizedDocument document = ocrClient.recognize(file);
        if (!document.pages().isEmpty()) {
            List<String> pageTexts = new ArrayList<>();
            for (OcrRecognizedDocument.OcrPage page : document.pages()) {
                String pageText = normalizeOcrText(page.text());
                if (!pageText.isBlank()) {
                    pageTexts.add("[Page " + page.pageNumber() + "]\n\n" + pageText);
                }
            }
            return String.join("\n\n", pageTexts);
        }
        return document.fullText() == null ? "" : document.fullText();
    }

    String normalizeOcrText(String rawText) {
        String normalized = rawText == null ? "" : rawText.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (normalized.isBlank()) {
            return "";
        }

        List<String> paragraphs = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String line : normalized.split("\n")) {
            String value = line.trim().replaceAll("[ \\t]+", " ");
            if (value.isBlank()) {
                flushParagraph(paragraphs, paragraph);
                continue;
            }
            if (isPageMarker(value) || isLikelyHeading(value)) {
                flushParagraph(paragraphs, paragraph);
                paragraphs.add(value);
                continue;
            }
            if (paragraph.isEmpty()) {
                paragraph.append(value);
                continue;
            }
            if (endsSentence(paragraph) || startsNewSemanticLine(value)) {
                flushParagraph(paragraphs, paragraph);
                paragraph.append(value);
            } else {
                appendWrappedLine(paragraph, value);
            }
        }
        flushParagraph(paragraphs, paragraph);
        return String.join("\n\n", paragraphs);
    }

    private void flushParagraph(List<String> paragraphs, StringBuilder paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        String value = paragraph.toString().trim();
        if (!value.isBlank() && !isLikelyNoiseLine(value)) {
            paragraphs.add(value);
        }
        paragraph.setLength(0);
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
}
