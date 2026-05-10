package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.util.SemanticChunkUtils;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ChunkingOptions;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ReportSemanticChunks;
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

    @Test
    void shouldBuildParentChildChunksForResearchReport() {
        String text = """
                投资要点

                我们认为行业景气度仍处于上行阶段，龙头公司盈利弹性有望继续释放。

                从供给端看，新增产能投放节奏低于预期，行业库存维持低位。

                从需求端看，下游订单连续改善，价格传导能力强于去年同期。

                风险提示

                原材料价格波动、政策变化、终端需求恢复不及预期。
                """;

        ReportSemanticChunks chunks = SemanticChunkUtils.chunkReport(
                text,
                new ChunkingOptions(20, 45, 80, 120, 10)
        );

        Assertions.assertFalse(chunks.parents().isEmpty());
        Assertions.assertFalse(chunks.children().isEmpty());
        Assertions.assertEquals("PARENT", chunks.parents().get(0).chunkType());
        Assertions.assertEquals("CHILD", chunks.children().get(0).chunkType());
        Assertions.assertTrue(chunks.children().get(0).text().contains("Section: 投资要点"));
        Assertions.assertTrue(chunks.children().stream().anyMatch(chunk -> chunk.sectionPath().equals("风险提示")));
    }
}
