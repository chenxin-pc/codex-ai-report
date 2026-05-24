package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.llm.QwenClient;
import com.example.aimilvusweb.common.prompt.PromptTemplateService;
import com.example.aimilvusweb.dto.RecommendStreamEventDTO;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.enums.QueryIntentEnum;
import com.example.aimilvusweb.enums.RecommendationOutputLevelEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportRecommendServiceTests类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
class ReportRecommendServiceTests {

    @Test
    void shouldStreamEvidenceBeforeModelDeltas() {
        QwenClient qwenClient = mock(QwenClient.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        ReportRetrievalService reportRetrievalService = mock(ReportRetrievalService.class);
        ResearchQueryIntentService intentService = mock(ResearchQueryIntentService.class);
        RecommendationEvidenceGuardrailService guardrailService = mock(RecommendationEvidenceGuardrailService.class);
        ReportRecommendService service = buildService(qwenClient, promptTemplateService, reportRetrievalService, intentService, guardrailService);

        when(intentService.classify("储能")).thenReturn(themeDecision("储能"));
        when(reportRetrievalService.retrieve("储能")).thenReturn(List.of(retrievedChunk()));
        when(guardrailService.evaluate(eq("储能"), eq(QueryIntentEnum.THEME_RESEARCH), any(List.class))).thenReturn(themeEvidence());
        when(promptTemplateService.loadTemplate("prompts/recommend-stream-system-prompt.txt")).thenReturn("system");
        when(promptTemplateService.render(eq("prompts/recommend-stream-user-prompt.txt"), any(Map.class))).thenReturn("user");
        when(qwenClient.chatStream("system", "user")).thenReturn(Flux.just("分析", "正文"));

        List<ServerSentEvent<RecommendStreamEventDTO>> events = service.recommendStream("储能").collectList().block();

        Assertions.assertNotNull(events);
        Assertions.assertEquals(List.of("status", "evidence", "status", "delta", "delta", "done"),
                events.stream().map(ServerSentEvent::event).toList());
        Assertions.assertEquals(1, events.get(1).data().top5().size());
        Assertions.assertEquals("分析", events.get(3).data().text());
        Assertions.assertEquals("正文", events.get(4).data().text());
    }

    @Test
    void shouldReturnModelMissingDeltaWhenQwenStreamIsEmpty() {
        QwenClient qwenClient = mock(QwenClient.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        ReportRetrievalService reportRetrievalService = mock(ReportRetrievalService.class);
        ResearchQueryIntentService intentService = mock(ResearchQueryIntentService.class);
        RecommendationEvidenceGuardrailService guardrailService = mock(RecommendationEvidenceGuardrailService.class);
        ReportRecommendService service = buildService(qwenClient, promptTemplateService, reportRetrievalService, intentService, guardrailService);

        when(intentService.classify("储能")).thenReturn(themeDecision("储能"));
        when(reportRetrievalService.retrieve("储能")).thenReturn(List.of(retrievedChunk()));
        when(guardrailService.evaluate(eq("储能"), eq(QueryIntentEnum.THEME_RESEARCH), any(List.class))).thenReturn(themeEvidence());
        when(promptTemplateService.loadTemplate("prompts/recommend-stream-system-prompt.txt")).thenReturn("system");
        when(promptTemplateService.render(eq("prompts/recommend-stream-user-prompt.txt"), any(Map.class))).thenReturn("user");
        when(qwenClient.chatStream("system", "user")).thenReturn(Flux.empty());

        List<ServerSentEvent<RecommendStreamEventDTO>> events = service.recommendStream("储能").collectList().block();

        Assertions.assertNotNull(events);
        Assertions.assertTrue(events.stream()
                .filter(event -> "delta".equals(event.event()))
                .anyMatch(event -> event.data().text().contains("未配置通义千问模型")));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void shouldReturnEvidenceMissingDeltaWhenRetrievalIsEmpty() {
        QwenClient qwenClient = mock(QwenClient.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        ReportRetrievalService reportRetrievalService = mock(ReportRetrievalService.class);
        ResearchQueryIntentService intentService = mock(ResearchQueryIntentService.class);
        RecommendationEvidenceGuardrailService guardrailService = mock(RecommendationEvidenceGuardrailService.class);
        ReportRecommendService service = buildService(qwenClient, promptTemplateService, reportRetrievalService, intentService, guardrailService);

        when(intentService.classify("储能")).thenReturn(themeDecision("储能"));
        when(reportRetrievalService.retrieve("储能")).thenReturn(List.of());
        when(guardrailService.evaluate(eq("储能"), eq(QueryIntentEnum.THEME_RESEARCH), any(List.class))).thenReturn(missingEvidence());

        List<ServerSentEvent<RecommendStreamEventDTO>> events = service.recommendStream("储能").collectList().block();

        Assertions.assertNotNull(events);
        Assertions.assertEquals(0, events.get(1).data().top5().size());
        Assertions.assertTrue(events.stream()
                .filter(event -> "delta".equals(event.event()))
                .anyMatch(event -> event.data().text().contains("未检索到可用证据")));
        verify(qwenClient, never()).chatStream(any(), any());
    }

    @Test
    void shouldReturnErrorEventWhenQwenStreamFails() {
        QwenClient qwenClient = mock(QwenClient.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        ReportRetrievalService reportRetrievalService = mock(ReportRetrievalService.class);
        ResearchQueryIntentService intentService = mock(ResearchQueryIntentService.class);
        RecommendationEvidenceGuardrailService guardrailService = mock(RecommendationEvidenceGuardrailService.class);
        ReportRecommendService service = buildService(qwenClient, promptTemplateService, reportRetrievalService, intentService, guardrailService);

        when(intentService.classify("储能")).thenReturn(themeDecision("储能"));
        when(reportRetrievalService.retrieve("储能")).thenReturn(List.of(retrievedChunk()));
        when(guardrailService.evaluate(eq("储能"), eq(QueryIntentEnum.THEME_RESEARCH), any(List.class))).thenReturn(themeEvidence());
        when(promptTemplateService.loadTemplate("prompts/recommend-stream-system-prompt.txt")).thenReturn("system");
        when(promptTemplateService.render(eq("prompts/recommend-stream-user-prompt.txt"), any(Map.class))).thenReturn("user");
        when(qwenClient.chatStream("system", "user")).thenReturn(Flux.error(new IllegalStateException("model failed")));

        List<ServerSentEvent<RecommendStreamEventDTO>> events = service.recommendStream("储能").collectList().block();

        Assertions.assertNotNull(events);
        Assertions.assertTrue(events.stream().anyMatch(event -> "error".equals(event.event())
                && event.data().message().contains("model failed")));
        Assertions.assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void shouldShortCircuitGreetingWithoutRetrieval() {
        QwenClient qwenClient = mock(QwenClient.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        ReportRetrievalService reportRetrievalService = mock(ReportRetrievalService.class);
        ResearchQueryIntentService intentService = mock(ResearchQueryIntentService.class);
        RecommendationEvidenceGuardrailService guardrailService = mock(RecommendationEvidenceGuardrailService.class);
        ReportRecommendService service = buildService(qwenClient, promptTemplateService, reportRetrievalService, intentService, guardrailService);

        when(intentService.classify("你好")).thenReturn(new ResearchQueryIntentService.QueryIntentDecision(
                QueryIntentEnum.REJECT, 1.0D, "命中非投研寒暄词", "你好", "RULE"
        ));

        RecommendRespDTO response = service.recommend("你好");

        Assertions.assertEquals("REJECT", response.inputIntent());
        Assertions.assertEquals("L0_REJECT", response.outputLevel());
        Assertions.assertTrue(response.recommendation().contains("投研分析模式"));
        verify(reportRetrievalService, never()).retrieve(any());
        verify(qwenClient, never()).chatForEntity(any(), any(), any());
    }

    @Test
    void shouldStreamGuidanceForRejectedInput() {
        QwenClient qwenClient = mock(QwenClient.class);
        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        ReportRetrievalService reportRetrievalService = mock(ReportRetrievalService.class);
        ResearchQueryIntentService intentService = mock(ResearchQueryIntentService.class);
        RecommendationEvidenceGuardrailService guardrailService = mock(RecommendationEvidenceGuardrailService.class);
        ReportRecommendService service = buildService(qwenClient, promptTemplateService, reportRetrievalService, intentService, guardrailService);

        when(intentService.classify("你好")).thenReturn(new ResearchQueryIntentService.QueryIntentDecision(
                QueryIntentEnum.REJECT, 1.0D, "命中非投研寒暄词", "你好", "RULE"
        ));

        List<ServerSentEvent<RecommendStreamEventDTO>> events = service.recommendStream("你好").collectList().block();

        Assertions.assertNotNull(events);
        Assertions.assertEquals(List.of("status", "evidence", "delta", "done"), events.stream().map(ServerSentEvent::event).toList());
        Assertions.assertTrue(events.stream().anyMatch(event -> "delta".equals(event.event())
                && event.data().text().contains("投研分析模式")));
        verify(reportRetrievalService, never()).retrieve(any());
        verify(qwenClient, never()).chatStream(any(), any());
    }

    /**
     * @Description: 构建推荐服务测试对象。
     * @Logic: 创建无Redis缓存的测试服务实例，并注入外部传入的模型、Prompt与检索依赖，便于定向控制流式分支。
     * @Param: qwenClient 模型调用mock；promptTemplateService Prompt服务mock；reportRetrievalService 检索服务mock。
     * @Return: 可用于流式推荐测试的ReportRecommendService实例。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private ReportRecommendService buildService(QwenClient qwenClient,
                                                PromptTemplateService promptTemplateService,
                                                ReportRetrievalService reportRetrievalService,
                                                ResearchQueryIntentService intentService,
                                                RecommendationEvidenceGuardrailService guardrailService) {
        ObjectProvider<StringRedisTemplate> redisTemplateProvider = mock(ObjectProvider.class);
        when(redisTemplateProvider.getIfAvailable()).thenReturn(null);
        return new ReportRecommendService(redisTemplateProvider, qwenClient, promptTemplateService, reportRetrievalService, intentService, guardrailService);
    }

    /**
     * @Description: 构建召回 chunk 测试数据。
     * @Logic: 组装包含标题、来源与章节元信息的Document，并包装为RetrievedChunk用于推荐证据链路测试。
     * @Param: 无。
     * @Return: 固定的RetrievedChunk测试样本。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private ReportRetrievalService.RetrievedChunk retrievedChunk() {
        Document document = new Document("child evidence", Map.of(
                "title", "储能行业深度",
                "source", "券商研报",
                "sectionPath", "投资要点"
        ));
        return new ReportRetrievalService.RetrievedChunk(document, 0.9D, "child evidence", "parent evidence");
    }

    private ResearchQueryIntentService.QueryIntentDecision themeDecision(String query) {
        return new ResearchQueryIntentService.QueryIntentDecision(QueryIntentEnum.THEME_RESEARCH, 0.9D, "命中主题词", query, "RULE");
    }

    private RecommendationEvidenceGuardrailService.EvidenceDecision themeEvidence() {
        return new RecommendationEvidenceGuardrailService.EvidenceDecision(
                RecommendationOutputLevelEnum.L2_THEME_RESEARCH,
                new RecommendRespDTO.EvidenceQualityRespDTO(true, true, true, true, true, List.of()),
                List.of(),
                java.util.Set.of("储能")
        );
    }

    private RecommendationEvidenceGuardrailService.EvidenceDecision missingEvidence() {
        return new RecommendationEvidenceGuardrailService.EvidenceDecision(
                RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED,
                new RecommendRespDTO.EvidenceQualityRespDTO(false, false, false, false, false, List.of("EVIDENCE_MISSING")),
                List.of("EVIDENCE_MISSING"),
                java.util.Set.of()
        );
    }
}
