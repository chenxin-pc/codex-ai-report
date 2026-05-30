package com.example.aimilvusweb.common.util;

import java.nio.charset.StandardCharsets;

/**
 * @Description: 文本编码修复工具，用于兼容 multipart 参数被误按 Windows-1252 解码后的中文乱码。
 * @Logic: 仅当回转后的中文占比更高且乱码特征更少时才替换原文，避免误伤正常文本。
 * @Param: 无。
 * @Return: 无（仅工具类定义）。
 * @author: cx
 * @Date: 2026-05-29 00:00:00
 */
public final class TextEncodingRepairUtils {

    private TextEncodingRepairUtils() {
    }

    /**
     * @Description: 修复 UTF-8 中文被误按 Windows-1252 解码后形成的乱码文本。
     * @Logic: 将原文按 Windows-1252 取回原始字节再按 UTF-8 解码，并通过字符特征评分决定是否采纳。
     * @Param: text 原始文本。
     * @Return: 修复后的文本；无法安全修复时返回原文。
     */
    public static String repairMojibake(String text) {
        if (text == null || text.isBlank() || !looksLikeMojibake(text)) {
            return text;
        }
        String repaired = decodeUtf8FromMisdecodedText(text);
        if (isBetterRepair(text, repaired)) {
            return repaired;
        }
        return text;
    }

    private static String decodeUtf8FromMisdecodedText(String text) {
        byte[] bytes = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            int value = toOriginalByte(text.charAt(i));
            if (value < 0) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int toOriginalByte(char ch) {
        return switch (ch) {
            case '\u20AC' -> 0x80;
            case '\u201A' -> 0x82;
            case '\u0192' -> 0x83;
            case '\u201E' -> 0x84;
            case '\u2026' -> 0x85;
            case '\u2020' -> 0x86;
            case '\u2021' -> 0x87;
            case '\u02C6' -> 0x88;
            case '\u2030' -> 0x89;
            case '\u0160' -> 0x8A;
            case '\u2039' -> 0x8B;
            case '\u0152' -> 0x8C;
            case '\u017D' -> 0x8E;
            case '\u2018' -> 0x91;
            case '\u2019' -> 0x92;
            case '\u201C' -> 0x93;
            case '\u201D' -> 0x94;
            case '\u2022' -> 0x95;
            case '\u2013' -> 0x96;
            case '\u2014' -> 0x97;
            case '\u02DC' -> 0x98;
            case '\u2122' -> 0x99;
            case '\u0161' -> 0x9A;
            case '\u203A' -> 0x9B;
            case '\u0153' -> 0x9C;
            case '\u017E' -> 0x9E;
            case '\u0178' -> 0x9F;
            default -> ch <= 0xFF ? ch : -1;
        };
    }

    private static boolean isBetterRepair(String original, String repaired) {
        if (repaired == null || repaired.isBlank() || repaired.contains("?")) {
            return false;
        }
        int originalHan = countHan(original);
        int repairedHan = countHan(repaired);
        int originalNoise = countMojibakeNoise(original);
        int repairedNoise = countMojibakeNoise(repaired);
        return repairedHan > originalHan && repairedNoise < originalNoise;
    }

    private static boolean looksLikeMojibake(String text) {
        return countMojibakeNoise(text) >= 2;
    }

    private static int countHan(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                count++;
            }
        }
        return count;
    }

    private static int countMojibakeNoise(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= '\u00C0' && ch <= '\u00FF')
                    || (ch >= '\u0080' && ch <= '\u009F')
                    || ch == '\uFFFD'
                    || isWindows1252Punctuation(ch)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isWindows1252Punctuation(char ch) {
        return ch == '\u2018'
                || ch == '\u2019'
                || ch == '\u201C'
                || ch == '\u201D'
                || ch == '\u2020'
                || ch == '\u2021'
                || ch == '\u2022'
                || ch == '\u2026'
                || ch == '\u2030'
                || ch == '\u20AC'
                || ch == '\u2122'
                || ch == '\u0152'
                || ch == '\u0153'
                || ch == '\u0160'
                || ch == '\u0161'
                || ch == '\u0178';
    }
}
