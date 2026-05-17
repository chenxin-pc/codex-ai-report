package com.example.aimilvusweb.common.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.springframework.web.multipart.MultipartFile;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @Description: PdfUtils类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public final class PdfUtils {

    private static final float HEADER_RATIO = 0.08F;
    private static final float FOOTER_RATIO = 0.06F;
    private static final float GUTTER_RATIO = 0.03F;
    private static final int MIN_COLUMN_TEXT_LENGTH = 180;
    private static final int MIN_PAGE_TEXT_LENGTH = 20;
    private static final int SHORT_LINE_MAX_CHARS = 14;
    private static final int MIN_BLOCK_CHARS = 30;

    /**
     * @Description: 初始化PdfUtils依赖与运行所需组件。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private PdfUtils() {
    }

    /**
     * @Description: 执行extractText相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public static String extractText(MultipartFile file) {
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            String text = extractTextByLayout(document);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("PDF contains no extractable text");
            }
            return text;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse PDF: " + e.getMessage(), e);
        }
    }

    /**
     * @Description: 执行extractTextByLayout相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static String extractTextByLayout(PDDocument document) throws IOException {
        List<String> pages = new ArrayList<>();
        for (PDPage page : document.getPages()) {
            String pageText = extractPageText(page);
            if (!pageText.isBlank()) {
                pages.add(pageText.trim());
            }
        }
        return String.join("\n\n", pages);
    }

    /**
     * @Description: 执行extractPageText相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static String extractPageText(PDPage page) throws IOException {
        PageRegions regions = buildPageRegions(page);
        RegionText regionText = extractRegionText(page, regions);
        PageLayout layout = detectLayout(regionText);
        List<String> orderedBlocks = resolveReadingOrder(layout, regionText);
        return cleanPageText(orderedBlocks);
    }

    /**
     * @Description: 构建目标对象或请求数据。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static PageRegions buildPageRegions(PDPage page) {
        PDRectangle mediaBox = page.getMediaBox();
        float width = mediaBox.getWidth();
        float height = mediaBox.getHeight();
        float header = height * HEADER_RATIO;
        float footer = height * FOOTER_RATIO;
        float contentHeight = Math.max(1F, height - header - footer);
        float gutter = Math.max(8F, width * GUTTER_RATIO);
        float halfWidth = width / 2F;
        float leftWidth = Math.max(1F, halfWidth - gutter / 2F);
        float rightX = Math.min(width - 1F, halfWidth + gutter / 2F);
        float rightWidth = Math.max(1F, width - rightX);

        Rectangle2D full = new Rectangle2D.Float(0F, header, width, contentHeight);
        Rectangle2D left = new Rectangle2D.Float(0F, header, leftWidth, contentHeight);
        Rectangle2D right = new Rectangle2D.Float(rightX, header, rightWidth, contentHeight);
        return new PageRegions(full, left, right);
    }

    /**
     * @Description: 执行extractRegionText相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static RegionText extractRegionText(PDPage page, PageRegions regions) throws IOException {
        PDFTextStripperByArea stripperByArea = new PDFTextStripperByArea();
        stripperByArea.setSortByPosition(true);
        stripperByArea.addRegion("full", regions.full());
        stripperByArea.addRegion("left", regions.left());
        stripperByArea.addRegion("right", regions.right());
        stripperByArea.extractRegions(page);

        String fullText = normalizeRaw(stripperByArea.getTextForRegion("full"));
        String leftText = normalizeRaw(stripperByArea.getTextForRegion("left"));
        String rightText = normalizeRaw(stripperByArea.getTextForRegion("right"));
        return new RegionText(fullText, leftText, rightText);
    }

    /**
     * @Description: 执行detectLayout相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static PageLayout detectLayout(RegionText text) {
        if (isLikelyTwoColumn(text.full(), text.left(), text.right())) {
            return PageLayout.TWO_COLUMN;
        }
        return PageLayout.SINGLE_COLUMN;
    }

    /**
     * @Description: 根据上下文解析并确定最终值。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static List<String> resolveReadingOrder(PageLayout layout, RegionText text) {
        List<String> ordered = new ArrayList<>();
        if (layout == PageLayout.TWO_COLUMN) {
            if (!text.left().isBlank()) {
                ordered.add(text.left());
            }
            if (!text.right().isBlank()) {
                ordered.add(text.right());
            }
            return ordered;
        }
        if (!text.full().isBlank()) {
            ordered.add(text.full());
        }
        return ordered;
    }

    /**
     * @Description: 执行cleanPageText相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static String cleanPageText(List<String> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        List<String> cleanedBlocks = new ArrayList<>();
        for (String block : blocks) {
            String value = normalizeRaw(block);
            if (value.isBlank()) {
                continue;
            }
            List<String> lines = value.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
            List<String> normalizedLines = normalizeLines(lines);
            if (normalizedLines.isEmpty()) {
                continue;
            }
            String normalizedBlock = String.join("\n", normalizedLines);
            if (isLikelyLowSemanticBlock(normalizedBlock)) {
                continue;
            }
            cleanedBlocks.add(normalizedBlock);
        }
        return normalizeRaw(String.join("\n\n", cleanedBlocks));
    }

    /**
     * @Description: 对输入数据进行规范化处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static List<String> normalizeLines(List<String> lines) {
        List<String> filtered = new ArrayList<>();
        StringBuilder shortLineBuffer = new StringBuilder();
        for (String line : lines) {
            if (isPageMarkLine(line) || isLikelyTableNoiseLine(line)) {
                continue;
            }
            if (line.length() <= SHORT_LINE_MAX_CHARS && !line.endsWith("。") && !line.endsWith(":") && !line.endsWith("：")) {
                if (shortLineBuffer.length() > 0) {
                    shortLineBuffer.append(' ');
                }
                shortLineBuffer.append(line);
                continue;
            }
            flushShortLineBuffer(filtered, shortLineBuffer);
            filtered.add(line);
        }
        flushShortLineBuffer(filtered, shortLineBuffer);
        return filtered;
    }

    /**
     * @Description: 刷新缓冲内容并落入结果集合。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static void flushShortLineBuffer(List<String> lines, StringBuilder shortLineBuffer) {
        if (shortLineBuffer.isEmpty()) {
            return;
        }
        lines.add(shortLineBuffer.toString().trim());
        shortLineBuffer.setLength(0);
    }

    /**
     * @Description: 判断是否满足PageMarkLine条件。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static boolean isPageMarkLine(String line) {
        String lowered = line.toLowerCase(Locale.ROOT);
        return lowered.matches("^page\\s*\\d+$") || lowered.matches("^\\d+\\s*/\\s*\\d+$");
    }

    /**
     * @Description: 判断是否满足LikelyTableNoiseLine条件。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static boolean isLikelyTableNoiseLine(String line) {
        String normalized = line.replaceAll("\\s+", "");
        if (normalized.isBlank()) {
            return true;
        }
        int digits = 0;
        int letters = 0;
        int symbols = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isDigit(ch)) {
                digits++;
            } else if (Character.isLetter(ch)) {
                letters++;
            } else {
                symbols++;
            }
        }
        int len = normalized.length();
        double numericRatio = digits / (double) len;
        double symbolRatio = symbols / (double) len;
        return (len <= 3 && digits > 0)
                || (numericRatio > 0.6D && len < 24)
                || (symbolRatio > 0.35D && letters < 3);
    }

    /**
     * @Description: 判断是否满足LikelyLowSemanticBlock条件。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static boolean isLikelyLowSemanticBlock(String block) {
        String normalized = block == null ? "" : block.replaceAll("\\s+", "");
        if (normalized.length() < MIN_BLOCK_CHARS) {
            return true;
        }
        int han = 0;
        int digits = 0;
        int symbols = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                han++;
            } else if (Character.isDigit(ch)) {
                digits++;
            } else if (!Character.isLetter(ch)) {
                symbols++;
            }
        }
        double hanRatio = han / (double) normalized.length();
        double noiseRatio = (digits + symbols) / (double) normalized.length();
        return hanRatio < 0.20D || noiseRatio > 0.65D;
    }

    /**
     * @Description: 判断是否满足LikelyTwoColumn条件。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static boolean isLikelyTwoColumn(String fullText, String leftText, String rightText) {
        if (leftText.length() < MIN_COLUMN_TEXT_LENGTH || rightText.length() < MIN_COLUMN_TEXT_LENGTH) {
            return false;
        }
        int fullLength = fullText.length();
        if (fullLength < MIN_PAGE_TEXT_LENGTH) {
            return false;
        }
        int combinedLength = leftText.length() + rightText.length();
        double ratio = combinedLength / (double) fullLength;
        return ratio > 1.2D;
    }

    /**
     * @Description: 对输入数据进行规范化处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private static String normalizeRaw(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private record PageRegions(Rectangle2D full, Rectangle2D left, Rectangle2D right) {
    }

    private record RegionText(String full, String left, String right) {
    }

    private enum PageLayout {
        SINGLE_COLUMN,
        TWO_COLUMN
    }
}
