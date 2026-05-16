package com.example.aimilvusweb.service;

import com.alibaba.fastjson2.JSON;
import com.example.aimilvusweb.common.llm.QwenClient;
import com.example.aimilvusweb.common.prompt.PromptTemplateService;
import com.example.aimilvusweb.dto.LlmRecommendRespDTO;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.dto.TopResultRespDTO;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ReportRecommendService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final QwenClient qwenClient;
    private final PromptTemplateService promptTemplateService;
    private final ReportRetrievalService reportRetrievalService;

    public ReportRecommendService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                  QwenClient qwenClient,
                                  PromptTemplateService promptTemplateService,
                                  ReportRetrievalService reportRetrievalService) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.qwenClient = qwenClient;
        this.promptTemplateService = promptTemplateService;
        this.reportRetrievalService = reportRetrievalService;
    }

    public RecommendRespDTO recommend(String query) {
        RecommendRespDTO cached = getCached(query);
        if (cached != null) {
            return cached;
        }

        List<ReportRetrievalService.RetrievedChunk> retrievedChunks = reportRetrievalService.retrieve(query);
        List<TopResultRespDTO> topResults = new ArrayList<>();
        StringBuilder evidenceBuilder = new StringBuilder();

        for (int i = 0; i < retrievedChunks.size(); i++) {
            ReportRetrievalService.RetrievedChunk retrievedChunk = retrievedChunks.get(i);
            Document doc = retrievedChunk.document();
            String title = String.valueOf(doc.getMetadata().getOrDefault("title", "unknown"));
            String source = String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"));
            topResults.add(new TopResultRespDTO(retrievedChunk.score(), title, retrievedChunk.chunkText(), source));
            evidenceBuilder.append("[Chunk ").append(i + 1).append("] ")
                    .append("title=").append(title)
                    .append(", source=").append(source)
                    .append(", section=").append(doc.getMetadata().getOrDefault("sectionPath", ""))
                    .append("\n")
                    .append(retrievedChunk.evidenceText())
                    .append("\n\n");
        }

        LlmRecommendRespDTO llm = generateLlmRecommendation(query, evidenceBuilder.toString());
        RecommendRespDTO response = new RecommendRespDTO(query, topResults, llm.analysis(), llm.recommendation(), llm.risks(), llm.citations());
        cache(query, response);
        return response;
    }

    private LlmRecommendRespDTO generateLlmRecommendation(String query, String evidence) {
        String systemPrompt = promptTemplateService.loadTemplate("prompts/recommend-system-prompt.txt");
        String userPrompt = promptTemplateService.render("prompts/recommend-user-prompt.txt",
                Map.of("query", query, "evidence", evidence));
        LlmRecommendRespDTO respDTO = qwenClient.chatForEntity(systemPrompt, userPrompt, LlmRecommendRespDTO.class);
        if (respDTO == null) {
            return new LlmRecommendRespDTO(
                    "未配置通义千问模型，当前仅返回检索结果。",
                    "请配置 DashScope 兼容 ChatModel 后启用 AI 推荐。",
                    List.of("模型未配置"),
                    List.of()
            );
        }
        return respDTO;
    }

    private RecommendRespDTO getCached(String query) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return null;
        }
        String value = redis.opsForValue().get(cacheKey(query));
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(value, RecommendRespDTO.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void cache(String query, RecommendRespDTO response) {
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(cacheKey(query), JSON.toJSONString(response), CACHE_TTL);
        } catch (Exception ignored) {
            // Redis cache failures must not block recommendations.
        }
    }

    private String cacheKey(String query) {
        return "ai-report:recommend:" + query.trim();
    }
}
