package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.common.util.SemanticChunkUtils;

/**
 * @Description: 召回证据文本 token 预算限制工具。
 * @Logic: 估算文本 token 数，未超限时原样返回，超限时按段落边界截断以保留研报语义完整性。
 * @Param: 无。
 * @Return: 无（工具类）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public final class RetrievalTextLimiter {

    /**
     * @Description: 私有构造器，禁止实例化工具类。
     * @Logic: 工具方法均为静态方法，无需对象状态。
     * @Param: 无。
     * @Return: 无（仅限制实例化）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private RetrievalTextLimiter() {
    }

    /**
     * @Description: 按 token 上限限制文本长度。
     * @Logic: 文本未超过上限时原样返回；超限时按空行切分段落并累加到预算边界。
     * @Param: text 待限制文本；maxTokens 最大 token 数。
     * @Return: 限制后的文本。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public static String limitTokens(String text, int maxTokens) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (SemanticChunkUtils.estimateTokens(text) <= maxTokens) {
            return text;
        }
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder limited = new StringBuilder();
        int tokens = 0;
        for (String paragraph : paragraphs) {
            int paragraphTokens = SemanticChunkUtils.estimateTokens(paragraph);
            if (tokens > 0 && tokens + paragraphTokens > maxTokens) {
                break;
            }
            if (!limited.isEmpty()) {
                limited.append("\n\n");
            }
            limited.append(paragraph.trim());
            tokens += paragraphTokens;
        }
        return limited.toString();
    }
}
