package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.llm.QwenClient;
import com.example.aimilvusweb.common.prompt.PromptTemplateService;
import com.example.aimilvusweb.dto.LlmRecommendRespDTO;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.dto.TopResultRespDTO;
import com.alibaba.fastjson2.JSON;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
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

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final QwenClient qwenClient;
    private final PromptTemplateService promptTemplateService;

    public ReportRecommendService(ObjectProvider<VectorStore> vectorStoreProvider,
                                  ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                  QwenClient qwenClient,
                                  PromptTemplateService promptTemplateService) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.redisTemplateProvider = redisTemplateProvider;
        this.qwenClient = qwenClient;
        this.promptTemplateService = promptTemplateService;
    }

    public RecommendRespDTO recommend(String query) {
        RecommendRespDTO cached = getCached(query);
        if (cached != null) {
            return cached;
        }

        // Top5 retrieval is purely vector ANN search in Milvus (Qwen embedding model configured in Spring AI).
        VectorStore vectorStore = requireVectorStore();

        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(5).build());
        if (docs == null) {
            docs = List.of();
        }

        List<TopResultRespDTO> top5 = new ArrayList<>();
        StringBuilder evidenceBuilder = new StringBuilder();

        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String title = String.valueOf(doc.getMetadata().getOrDefault("title", "unknown"));
            String source = String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"));
            String chunkText = doc.getText();
            Double score = resolveScore(doc);

            top5.add(new TopResultRespDTO(score, title, chunkText, source));
            evidenceBuilder.append("[Chunk ").append(i + 1).append("] ")
                    .append("title=").append(title)
                    .append(", source=").append(source)
                    .append("\n")
                    .append(chunkText)
                    .append("\n\n");
        }

        LlmRecommendRespDTO llm = generateLlmRecommendation(query, evidenceBuilder.toString());
        RecommendRespDTO response = new RecommendRespDTO(query, top5, llm.analysis(), llm.recommendation(), llm.risks(), llm.citations());
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

    private Double resolveScore(Document doc) {
        Object score = doc.getMetadata().get("score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        Object distance = doc.getMetadata().get("distance");
        if (distance instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private VectorStore requireVectorStore() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new IllegalStateException("VectorStore is not configured. Set spring.ai.vectorstore.type=milvus and Milvus properties.");
        }
        return vectorStore;
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
            // no-op
        }
    }

    private String cacheKey(String query) {
        return "ai-report:recommend:" + query.trim();
    }
}
