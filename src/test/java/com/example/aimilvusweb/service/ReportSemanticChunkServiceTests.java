package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.llm.QwenClient;
import com.example.aimilvusweb.common.prompt.PromptTemplateService;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ReportSemanticChunks;
import com.example.aimilvusweb.dto.LlmChunkPlanRespDTO;
import com.example.aimilvusweb.dto.LlmChunkSegmentRespDTO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportSemanticChunkServiceTests {

    @Test
    void shouldUseLlmSegmentsWhenBoundariesAreValid() {
        QwenClient qwenClient = mock(QwenClient.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        ReportSemanticChunkService service = new ReportSemanticChunkService(qwenClient, promptTemplateService);

        when(promptTemplateService.loadTemplate("prompts/chunk-boundary-system-prompt.txt")).thenReturn("system");
        when(promptTemplateService.render(eq("prompts/chunk-boundary-user-prompt.txt"), any(Map.class))).thenReturn("user");
        when(qwenClient.chatForEntity("system", "user", LlmChunkPlanRespDTO.class))
                .thenReturn(new LlmChunkPlanRespDTO(List.of(
                        new LlmChunkSegmentRespDTO(1, 3, "行业供需逻辑", "INDUSTRY_ANALYSIS", 0.9),
                        new LlmChunkSegmentRespDTO(4, 4, "风险提示", "RISK", 0.95)
                )));

        String text = """
                投资要点

                我们认为行业景气度仍处于上行阶段。

                从供给端看，新增产能投放节奏低于预期。

                从需求端看，下游订单连续改善。

                风险提示：原材料价格波动。
                """;

        ReportSemanticChunks chunks = service.chunk(text);

        Assertions.assertEquals(2, chunks.parents().size());
        Assertions.assertTrue(chunks.parents().get(0).sectionPath().contains("行业供需逻辑"));
        Assertions.assertTrue(chunks.parents().get(1).sectionPath().contains("风险提示"));
        Assertions.assertFalse(chunks.children().isEmpty());
    }
}
