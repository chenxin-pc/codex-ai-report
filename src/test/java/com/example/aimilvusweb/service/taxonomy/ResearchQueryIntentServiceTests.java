package com.example.aimilvusweb.service.taxonomy;

import com.example.aimilvusweb.common.llm.QwenClient;
import com.example.aimilvusweb.common.prompt.PromptTemplateService;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.dto.LlmQueryIntentRespDTO;
import com.example.aimilvusweb.enums.QueryIntentEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: ResearchQueryIntentService 测试，验证规则优先和 LLM 兜底分类。
 * @Logic: 使用真实本地词典和 mock 模型，覆盖高置信规则、LLM 结构化分类、低置信保守拒绝。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-24 00:45:00
 */
class ResearchQueryIntentServiceTests {

    @Test
    void shouldRejectGreetingByRulesWithoutLlm() {
        QwenClient qwenClient = mock(QwenClient.class);
        ResearchQueryIntentService service = buildService(qwenClient, mock(PromptTemplateService.class), new ReportQualityProperties());

        ResearchQueryIntentService.QueryIntentDecision decision = service.classify("你好");

        Assertions.assertEquals(QueryIntentEnum.REJECT, decision.intent());
        Assertions.assertEquals("RULE", decision.source());
        verify(qwenClient, never()).chatForEntity(any(), any(), any());
    }

    @Test
    void shouldClassifyThemeQueryByRulesWithoutLlm() {
        QwenClient qwenClient = mock(QwenClient.class);
        ResearchQueryIntentService service = buildService(qwenClient, mock(PromptTemplateService.class), new ReportQualityProperties());

        ResearchQueryIntentService.QueryIntentDecision decision = service.classify("分析港口行业近期景气度");

        Assertions.assertEquals(QueryIntentEnum.THEME_RESEARCH, decision.intent());
        Assertions.assertEquals("RULE", decision.source());
        verify(qwenClient, never()).chatForEntity(any(), any(), any());
    }

    @Test
    void shouldClassifyTickerQueryAsAnalyze() {
        ResearchQueryIntentService service = buildService(mock(QwenClient.class), mock(PromptTemplateService.class), new ReportQualityProperties());

        ResearchQueryIntentService.QueryIntentDecision decision = service.classify("000582咋样");

        Assertions.assertEquals(QueryIntentEnum.ANALYZE, decision.intent());
        Assertions.assertEquals("RULE", decision.source());
    }

    @Test
    void shouldUseLlmWhenRulesAreLowConfidence() {
        QwenClient qwenClient = mock(QwenClient.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        ResearchQueryIntentService service = buildService(qwenClient, promptTemplateService, new ReportQualityProperties());

        when(promptTemplateService.loadTemplate("prompts/query-intent-system-prompt.txt")).thenReturn("system");
        when(promptTemplateService.render(eq("prompts/query-intent-user-prompt.txt"), any(Map.class))).thenReturn("user");
        when(qwenClient.chatForEntity("system", "user", LlmQueryIntentRespDTO.class))
                .thenReturn(new LlmQueryIntentRespDTO("THEME_RESEARCH", 0.8D, "口语化主题问题", "分析药店板块"));

        ResearchQueryIntentService.QueryIntentDecision decision = service.classify("能不能看看这块");

        Assertions.assertEquals(QueryIntentEnum.THEME_RESEARCH, decision.intent());
        Assertions.assertEquals("LLM", decision.source());
        Assertions.assertEquals("分析药店板块", decision.normalizedQuery());
    }

    @Test
    void shouldRejectWhenLlmConfidenceIsLow() {
        QwenClient qwenClient = mock(QwenClient.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        ReportQualityProperties properties = new ReportQualityProperties();
        ResearchQueryIntentService service = buildService(qwenClient, promptTemplateService, properties);

        when(promptTemplateService.loadTemplate("prompts/query-intent-system-prompt.txt")).thenReturn("system");
        when(promptTemplateService.render(eq("prompts/query-intent-user-prompt.txt"), any(Map.class))).thenReturn("user");
        when(qwenClient.chatForEntity("system", "user", LlmQueryIntentRespDTO.class))
                .thenReturn(new LlmQueryIntentRespDTO("THEME_RESEARCH", 0.4D, "低置信", "这个可以吗"));

        ResearchQueryIntentService.QueryIntentDecision decision = service.classify("这个可以吗");

        Assertions.assertEquals(QueryIntentEnum.REJECT, decision.intent());
        Assertions.assertEquals("FALLBACK", decision.source());
    }

    private ResearchQueryIntentService buildService(QwenClient qwenClient,
                                                    PromptTemplateService promptTemplateService,
                                                    ReportQualityProperties properties) {
        QueryGuardrailDictionaryService dictionaryService = new QueryGuardrailDictionaryService(new DefaultResourceLoader(), properties);
        return new ResearchQueryIntentService(dictionaryService, properties, qwenClient, promptTemplateService);
    }
}
