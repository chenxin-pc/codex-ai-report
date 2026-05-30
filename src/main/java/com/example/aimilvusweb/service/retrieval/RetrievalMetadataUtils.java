package com.example.aimilvusweb.service.retrieval;

import org.springframework.ai.document.Document;

import java.util.Locale;

/**
 * @Description: 召回链路 metadata 与文本规范化工具。
 * @Logic: 提供 Document metadata 读取、去重文本规范化和 overlap 文本规范化能力，供候选处理与策略组件复用。
 * @Param: 无。
 * @Return: 无（工具类）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public final class RetrievalMetadataUtils {

    /**
     * @Description: 私有构造器，禁止实例化工具类。
     * @Logic: 工具方法均为静态方法，无需创建对象。
     * @Param: 无。
     * @Return: 无（仅限制实例化）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private RetrievalMetadataUtils() {
    }

    /**
     * @Description: 读取 Document metadata 字符串值。
     * @Logic: metadata 缺失、值为 null 或空白时统一返回空字符串，避免调用方重复判空。
     * @Param: document Milvus 返回的文档；key metadata 字段名。
     * @Return: 裁剪后的 metadata 字符串；无值时返回空字符串。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public static String metadataText(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * @Description: 规范化用于候选去重的文本。
     * @Logic: null 转空，折叠连续空白并转小写，降低换行、空格和大小写差异对去重的影响。
     * @Param: text 原始文本。
     * @Return: 规范化后的比较文本。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public static String normalizeForDedup(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @Description: 规范化用于 query overlap 重排的文本。
     * @Logic: null 转空，移除所有空白并转小写，让字符重合度更关注内容本身。
     * @Param: text 原始文本。
     * @Return: 规范化后的 overlap 比较文本。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public static String normalizeForOverlap(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
