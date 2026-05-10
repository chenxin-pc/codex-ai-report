package com.example.aimilvusweb.common.util;

import java.util.ArrayList;
import java.util.List;

public final class SemanticChunkUtils {

    private SemanticChunkUtils() {
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
}
