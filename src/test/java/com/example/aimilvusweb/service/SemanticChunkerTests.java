package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class SemanticChunkerTests {

    @Test
    void shouldChunkByParagraphWindowWithOverlap() {
        String text = "P1\n\nP2\n\nP3\n\nP4";

        List<String> chunks = SemanticChunkUtils.chunkByParagraphWindow(text, 2, 1);

        Assertions.assertEquals(3, chunks.size());
        Assertions.assertEquals("P1\n\nP2", chunks.get(0));
    }
}
