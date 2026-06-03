package com.example.aimilvusweb.service.ingest;

import com.alibaba.fastjson2.JSON;
import com.example.aimilvusweb.common.ocr.OcrClient;
import com.example.aimilvusweb.common.ocr.OcrRecognizedDocument;
import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ParagraphAtom;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
/**
 * @Description: ReportOcrParseService类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class ReportOcrParseService {
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("(?m)^\\s*```[A-Za-z0-9_-]*\\s*$");
    private static final Pattern LATEX_SECTION = Pattern.compile("\\\\(?:sub)*section\\*?\\{([^{}]*)}");
    private static final Pattern LATEX_TEXT_STYLE = Pattern.compile("\\\\(?:textbf|textit|emph|underline)\\{([^{}]*)}");
    private static final Pattern LATEX_BEGIN_END = Pattern.compile("\\\\(?:begin|end)\\{[^{}]+}(?:\\{[^{}]*})?");
    private static final Pattern LATEX_COMMAND_WITH_ARG = Pattern.compile("\\\\(?:vspace|hspace|multicolumn)\\*?(?:\\[[^\\]]*])?\\{[^{}]*}(?:\\{[^{}]*})?(?:\\{[^{}]*})?");
    private static final Pattern LATEX_SIMPLE_COMMAND = Pattern.compile("\\\\(?:hline|hrule|noindent|centering|raggedleft|raggedright)\\b");
    private static final Pattern LATEX_ITEM = Pattern.compile("\\\\item(?:\\s+)?");
    private static final Pattern LATEX_ROW_SEPARATOR = Pattern.compile("\\\\\\\\");

    private final OcrClient ocrClient;

    /**
     * @Description: 初始化ReportOcrParseService依赖与运行所需组件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public ReportOcrParseService(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    /**
     * @Description: 解析输入内容并输出结构化结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public String parse(MultipartFile file) {
        return parseDetailed(file).cleanedText();
    }

    /**
     * @Description: 解析输入内容并输出结构化结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 对输入数据进行规范化处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private ReportOcrParseResult normalizeDocument(OcrRecognizedDocument document) {
        if (!document.pages().isEmpty()) {
            List<OcrPageResult> pages = new ArrayList<>();
            List<String> rawPageTexts = new ArrayList<>();
            List<String> cleanedPageTexts = new ArrayList<>();
            for (OcrRecognizedDocument.OcrPage page : document.pages()) {
                NormalizeResult normalized = normalizeOcrTextWithDiagnostics(page.text());
                rawPageTexts.add(page.text() == null ? "" : page.text());
                if (!normalized.text().isBlank()) {
                    cleanedPageTexts.add(normalized.text());
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

    /**
     * @Description: OCR 文本快捷规范化入口，仅返回清洗文本本体。
     * @Logic: 复用 normalizeOcrTextWithDiagnostics，丢弃诊断信息，适合只需要正文的调用场景。
     * @Param: rawText OCR 原始文本。
     * @Return: 清洗后的规范化文本。
     * @author: cx
     * @Date: 2026-05-21 23:20:00
     */
    String normalizeOcrText(String rawText) {
        return normalizeOcrTextWithDiagnostics(rawText).text();
    }

    /**
     * @Description: OCR 文本规范化核心流程，输出清洗后的段落文本与诊断信息。
     * @Logic: 先统一换行与空白，再清理 Markdown/LaTeX 标记；随后按行重组段落并过滤疑似噪声行，最终汇总诊断字段返回。
     * @Param: rawText OCR 原始文本。
     * @Return: NormalizeResult，包含规范化文本与诊断 JSON。
     * @author: cx
     * @Date: 2026-05-21 23:20:00
     */
    NormalizeResult normalizeOcrTextWithDiagnostics(String rawText) {
        String normalized = rawText == null ? "" : rawText.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (normalized.isBlank()) {
            return new NormalizeResult("", JSON.toJSONString(Map.of("status", "blank")));
        }
        MarkupCleanResult markupCleanResult = cleanMarkup(normalized);
        normalized = markupCleanResult.text();

        List<String> paragraphs = new ArrayList<>();
        List<String> removedNoiseLines = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        String[] lines = normalized.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String value = lines[i].trim().replaceAll("[ \\t]+", " ");
            if (value.isBlank()) {
                String nextNonBlank = nextNonBlankLine(lines, i + 1);
                if (shouldKeepParagraphOpen(paragraph, nextNonBlank)) {
                    continue;
                }
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
        Map<String, Object> diagnosticsMap = new LinkedHashMap<>();
        diagnosticsMap.put("removedNoiseLineCount", removedNoiseLines.size());
        diagnosticsMap.put("removedNoiseLines", removedNoiseLines);
        diagnosticsMap.put("markupCleaned", markupCleanResult.cleaned());
        diagnosticsMap.put("markupCleanCounts", markupCleanResult.counts());
        String diagnostics = JSON.toJSONString(diagnosticsMap);
        return new NormalizeResult(String.join("\n\n", paragraphs), diagnostics);
    }

    /**
     * @Description: 获取从指定下标开始的下一个非空行（已做 trim 和空白折叠）。
 * @Logic: 用于处理 OCR 伪空行导致的过度分段，通过前瞻判断是否应跨空行续接同一段落。
 * @Param: lines 全量行数组；startInclusive 起始下标（含）。
 * @Return: 下一个非空行；若不存在返回空字符串。
     * @author: cx
     * @Date: 2026-05-23 23:30:00
     */
    private String nextNonBlankLine(String[] lines, int startInclusive) {
        for (int i = startInclusive; i < lines.length; i++) {
            String value = lines[i].trim().replaceAll("[ \\t]+", " ");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * @Description: 判断遇到空行时是否保持段落开启（不立即 flush）。
 * @Logic: 当当前段落未以句末符号结束，且下一行更像同段续写而非标题/新语义单元时，认为是 OCR 伪空行并跨空行续接。
 * @Param: paragraph 当前段落缓冲；nextNonBlank 前瞻到的下一条非空行。
 * @Return: true 表示跳过本次空行并继续拼接；false 表示按真实段落边界 flush。
     * @author: cx
     * @Date: 2026-05-23 23:30:00
     */
    private boolean shouldKeepParagraphOpen(StringBuilder paragraph, String nextNonBlank) {
        if (paragraph.isEmpty() || nextNonBlank == null || nextNonBlank.isBlank()) {
            return false;
        }
        if (isPageMarker(nextNonBlank) || isLikelyHeading(nextNonBlank) || startsNewSemanticLine(nextNonBlank)) {
            return false;
        }
        return !endsSentence(paragraph);
    }

    /**
     * @Description: 执行cleanMarkup相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private MarkupCleanResult cleanMarkup(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String value = text;
        value = replaceAndCount(value, MARKDOWN_FENCE, "", counts, "markdownFence");
        value = unwrapAndCount(value, LATEX_SECTION, counts, "latexSection");
        value = unwrapAndCount(value, LATEX_TEXT_STYLE, counts, "latexTextStyle");
        value = replaceAndCount(value, LATEX_ITEM, "", counts, "latexItem");
        value = replaceAndCount(value, LATEX_BEGIN_END, "\n", counts, "latexContainer");
        value = replaceAndCount(value, LATEX_COMMAND_WITH_ARG, "", counts, "latexCommandWithArg");
        value = replaceAndCount(value, LATEX_SIMPLE_COMMAND, "", counts, "latexSimpleCommand");
        value = replaceAndCount(value, LATEX_ROW_SEPARATOR, "\n", counts, "latexRowSeparator");
        value = value.replace("\\%", "%")
                .replace("\\&", "&")
                .replace("\\_", "_")
                .replace("\\textbackslash", "\\");
        if (value.contains("&")) {
            counts.merge("tableCellSeparator", countOccurrences(value, "&"), Integer::sum);
            value = value.replace("&", " | ");
        }
        value = value.replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n[ \\t]+", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return new MarkupCleanResult(value, counts.values().stream().mapToInt(Integer::intValue).sum() > 0, counts);
    }

    /**
     * @Description: 执行replaceAndCount相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String replaceAndCount(String text, Pattern pattern, String replacement, Map<String, Integer> counts, String key) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        if (count == 0) {
            return text;
        }
        counts.merge(key, count, Integer::sum);
        return pattern.matcher(text).replaceAll(replacement);
    }

    /**
     * @Description: 执行unwrapAndCount相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String unwrapAndCount(String text, Pattern pattern, Map<String, Integer> counts, String key) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder builder = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            count++;
            matcher.appendReplacement(builder, Matcher.quoteReplacement(matcher.group(1)));
        }
        if (count == 0) {
            return text;
        }
        matcher.appendTail(builder);
        counts.merge(key, count, Integer::sum);
        return builder.toString();
    }

    /**
     * @Description: 执行countOccurrences相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private int countOccurrences(String text, String value) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

    /**
     * @Description: 刷新缓冲内容并落入结果集合。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 将文本拆分为最小语义单元。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 合并多源数据并返回结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 判断是否满足PageMarker条件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private boolean isPageMarker(String line) {
        return line.matches("^\\[Page\\s+\\d+\\]$") || line.matches("^第\\s*\\d+\\s*页$");
    }

    /**
     * @Description: 判断是否满足LikelyHeading条件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private boolean isLikelyHeading(String line) {
        String value = line.trim();
        if (value.length() > 48 || value.matches(".*[。；，,.!?].*") || startsNewSemanticLine(value)) {
            return false;
        }
        return value.matches("^([一二三四五六七八九十]+[、.]|\\d+(\\.\\d+)*[、.)]?)\\s*\\S+.*")
                || value.matches(".*(摘要|要点|观点|评级|行业|公司|财务|盈利|估值|风险|提示|结论|投资|供给|需求|库存|价格|成本|政策).*");
    }

    /**
     * @Description: 执行endsSentence相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private boolean endsSentence(StringBuilder paragraph) {
        if (paragraph.isEmpty()) {
            return false;
        }
        char last = paragraph.charAt(paragraph.length() - 1);
        return last == '。' || last == '！' || last == '？' || last == '；'
                || last == '.' || last == '!' || last == '?' || last == ';';
    }

    /**
     * @Description: 执行startsNewSemanticLine相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private boolean startsNewSemanticLine(String line) {
        return line.matches("^(首先|其次|再次|最后|一方面|另一方面|从供给|从需求|盈利预测|估值|风险|投资建议|我们认为).*");
    }

    /**
     * @Description: 追加文本片段并维护上下文。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 执行needsSpace相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private boolean needsSpace(char left, char right) {
        return isAsciiLetterOrDigit(left) && isAsciiLetterOrDigit(right);
    }

    /**
     * @Description: 判断是否满足AsciiLetterOrDigit条件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private boolean isAsciiLetterOrDigit(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    /**
     * @Description: 判断是否满足LikelyNoiseLine条件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 文本规范化结果载体。
     * @Logic: 保存可用于后续切片的规范化文本，以及清洗过程的诊断信息。
     * @Param: text 规范化文本；diagnostics 诊断 JSON。
     * @Return: 无（仅数据载体）。
     * @author: cx
     * @Date: 2026-05-21 23:20:00
     */
    record NormalizeResult(String text, String diagnostics) {
    }

    /**
     * @Description: 标记清洗结果载体。
     * @Logic: 记录清洗后文本、是否发生清洗以及各类标记清洗计数。
     * @Param: text 清洗后文本；cleaned 是否发生清洗；counts 清洗项计数字典。
     * @Return: 无（仅数据载体）。
     * @author: cx
     * @Date: 2026-05-21 23:20:00
     */
    record MarkupCleanResult(String text, boolean cleaned, Map<String, Integer> counts) {
    }

    /**
     * @Description: 页级 OCR 结果载体。
     * @Logic: 统一保存每页原文、清洗文本与页级诊断，供入库与质量观测使用。
     * @Param: pageNumber 页码；rawText 原文；cleanedText 清洗文；diagnostics 诊断信息。
     * @Return: 无（仅数据载体）。
     * @author: cx
     * @Date: 2026-05-21 23:20:00
     */
    public record OcrPageResult(
            int pageNumber,
            String rawText,
            String cleanedText,
            String diagnostics
    ) {
    }

    /**
     * @Description: OCR 解析总结果载体。
     * @Logic: 汇总整文原文、整文清洗文、页级结构和段落原子，作为切分阶段统一输入。
     * @Param: rawText 原始全文；cleanedText 清洗全文；pages 页级结果；atoms 段落原子列表。
     * @Return: 无（仅数据载体）。
     * @author: cx
     * @Date: 2026-05-21 23:20:00
     */
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
