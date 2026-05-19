package com.example.aimilvusweb.service;

import com.alibaba.fastjson2.JSON;
import com.example.aimilvusweb.common.llm.QwenClient;
import com.example.aimilvusweb.common.prompt.PromptTemplateService;
import com.example.aimilvusweb.dto.LlmRecommendRespDTO;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.dto.RecommendStreamEventDTO;
import com.example.aimilvusweb.dto.TopResultRespDTO;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
/**
 * @Description: ReportRecommendService类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class ReportRecommendService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final String EVENT_STATUS = "status";
    private static final String EVENT_EVIDENCE = "evidence";
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";
    private static final String STAGE_RETRIEVING = "retrieving";
    private static final String STAGE_GENERATING = "generating";
    private static final String STAGE_COMPLETED = "completed";
    private static final String STAGE_ERROR = "error";
    private static final String MODEL_MISSING_MESSAGE = "未配置通义千问模型，当前仅返回检索证据。";
    private static final String EVIDENCE_MISSING_MESSAGE = "未检索到可用证据，无法基于研报内容生成可靠推荐。";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final QwenClient qwenClient;
    private final PromptTemplateService promptTemplateService;
    private final ReportRetrievalService reportRetrievalService;

    /**
     * @Description: 初始化ReportRecommendService依赖与运行所需组件。
     * @Logic: 保存缓存、模型调用、Prompt模板与检索服务依赖，供同步与流式推荐链路复用。
     * @Param: redisTemplateProvider Redis模板提供器；qwenClient 模型调用客户端；promptTemplateService Prompt模板服务；reportRetrievalService 检索服务。
     * @Return: 无（仅初始化对象状态）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    public ReportRecommendService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                  QwenClient qwenClient,
                                  PromptTemplateService promptTemplateService,
                                  ReportRetrievalService reportRetrievalService) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.qwenClient = qwenClient;
        this.promptTemplateService = promptTemplateService;
        this.reportRetrievalService = reportRetrievalService;
    }

    /**
     * @Description: 生成推荐结果并返回推荐响应。
     * @Logic: 先查缓存命中并返回；未命中则构建证据上下文、调用结构化推荐、写入缓存后返回结果。
     * @Param: query 用户投研问题。
     * @Return: 结构化推荐结果对象，包含检索Top5与模型输出字段。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    public RecommendRespDTO recommend(String query) {
        RecommendRespDTO cached = getCached(query);
        if (cached != null) {
            return cached;
        }

        RecommendationContext context = buildRecommendationContext(query);
        LlmRecommendRespDTO llm = generateLlmRecommendation(query, context.evidence());
        RecommendRespDTO response = new RecommendRespDTO(query, context.topResults(), llm.analysis(), llm.recommendation(), llm.risks(), llm.citations());
        cache(query, response);
        return response;
    }

    /**
     * @Description: 生成推荐结果并以 SSE 事件流返回状态、证据和模型增量。
     * @Logic: 先构建检索上下文并发送status/evidence阶段事件，再拼接模型增量流，统一补发done；任意异常转为error并结束。
     * @Param: query 用户投研问题。
     * @Return: SSE事件流，按event区分status、evidence、delta、done和error。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    public Flux<ServerSentEvent<RecommendStreamEventDTO>> recommendStream(String query) {
        return Flux.defer(() -> {
            RecommendationContext context = buildRecommendationContext(query);
            Flux<ServerSentEvent<RecommendStreamEventDTO>> head = Flux.just(
                    statusEvent(STAGE_RETRIEVING, "正在检索相关研报证据"),
                    event(EVENT_EVIDENCE, new RecommendStreamEventDTO(STAGE_RETRIEVING, "检索完成", null, context.topResults())),
                    statusEvent(STAGE_GENERATING, "正在生成推荐分析")
            );
            return Flux.concat(head, streamLlmRecommendation(query, context), Flux.just(doneEvent()));
        }).onErrorResume(e -> Flux.just(errorEvent(e.getMessage()), doneEvent()));
    }

    /**
     * @Description: 基于query与证据上下文生成流式推荐事件；证据为空时直接返回证据不足提示，模型无增量时返回模型未配置提示，模型异常时返回error事件。
     * @Logic: 证据为空直接输出证据不足delta；证据存在时加载流式Prompt并调用模型增量流，过滤空片段，空流降级提示，异常转error事件。
     * @Param: query 用户投研问题；context 检索证据上下文（Top5与拼接证据文本）。
     * @Return: 模型阶段SSE事件流，事件主要为delta，异常时为error。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private Flux<ServerSentEvent<RecommendStreamEventDTO>> streamLlmRecommendation(String query, RecommendationContext context) {
        if (context.topResults().isEmpty()) {
            return Flux.just(deltaEvent(EVIDENCE_MISSING_MESSAGE));
        }
        String systemPrompt = promptTemplateService.loadTemplate("prompts/recommend-stream-system-prompt.txt");
        String userPrompt = promptTemplateService.render("prompts/recommend-stream-user-prompt.txt",
                Map.of("query", query, "evidence", context.evidence()));
        return qwenClient.chatStream(systemPrompt, userPrompt)
                .filter(content -> content != null && !content.isBlank())
                .map(this::deltaEvent)
                .switchIfEmpty(Flux.just(deltaEvent(MODEL_MISSING_MESSAGE)))
                .onErrorResume(e -> Flux.just(errorEvent(e.getMessage())));
    }

    /**
     * @Description: 使用同步推荐Prompt生成结构化推荐结果；输入query和证据文本，输出LlmRecommendRespDTO，模型未配置时返回默认降级文案。
     * @Logic: 加载同步推荐Prompt并渲染变量后调用结构化模型输出；当模型客户端不可用时返回固定降级结果。
     * @Param: query 用户投研问题；evidence 拼接后的召回证据文本。
     * @Return: 结构化推荐DTO，包含analysis、recommendation、risks与citations。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
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

    /**
     * @Description: 构建推荐所需召回结果和证据文本。
     * @Logic: 调用检索服务获取候选chunk，构建前端Top5展示对象，并将证据按Chunk编号与元信息拼接为模型输入文本。
     * @Param: query 用户投研问题。
     * @Return: 推荐上下文对象，包含Top5列表与拼接后的证据文本。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private RecommendationContext buildRecommendationContext(String query) {
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
        return new RecommendationContext(topResults, evidenceBuilder.toString());
    }

    /**
     * @Description: 构建阶段状态事件。
     * @Logic: 使用统一事件构造器包装阶段与消息，生成status类型SSE事件。
     * @Param: stage 阶段标识；message 阶段说明文本。
     * @Return: status事件对象。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private ServerSentEvent<RecommendStreamEventDTO> statusEvent(String stage, String message) {
        return event(EVENT_STATUS, new RecommendStreamEventDTO(stage, message, null, List.of()));
    }

    /**
     * @Description: 构建模型文本增量事件。
     * @Logic: 将模型增量文本写入事件数据并标记为delta事件。
     * @Param: text 模型输出的增量文本片段。
     * @Return: delta事件对象。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private ServerSentEvent<RecommendStreamEventDTO> deltaEvent(String text) {
        return event(EVENT_DELTA, new RecommendStreamEventDTO(STAGE_GENERATING, null, text, List.of()));
    }

    /**
     * @Description: 构建完成事件。
     * @Logic: 生成固定completed阶段的done事件，作为流式响应结束标记。
     * @Param: 无。
     * @Return: done事件对象。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private ServerSentEvent<RecommendStreamEventDTO> doneEvent() {
        return event(EVENT_DONE, new RecommendStreamEventDTO(STAGE_COMPLETED, "completed", null, List.of()));
    }

    /**
     * @Description: 构建错误事件。
     * @Logic: 对空错误消息做兜底后封装为error阶段事件，避免向前端输出空信息。
     * @Param: message 原始错误消息。
     * @Return: error事件对象。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private ServerSentEvent<RecommendStreamEventDTO> errorEvent(String message) {
        String safeMessage = message == null || message.isBlank() ? "推荐流式输出失败" : message;
        return event(EVENT_ERROR, new RecommendStreamEventDTO(STAGE_ERROR, safeMessage, null, List.of()));
    }

    /**
     * @Description: 构建 SSE 事件。
     * @Logic: 按事件名与事件数据创建ServerSentEvent实例，统一服务内事件封装逻辑。
     * @Param: eventName SSE事件名；data 事件负载对象。
     * @Return: 通用SSE事件对象。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private ServerSentEvent<RecommendStreamEventDTO> event(String eventName, RecommendStreamEventDTO data) {
        return ServerSentEvent.<RecommendStreamEventDTO>builder(data)
                .event(eventName)
                .build();
    }

    /**
     * @Description: 按query读取Redis缓存推荐结果；缓存不存在、为空或反序列化失败时返回null，不抛出异常中断主流程。
     * @Logic: 获取Redis模板并读取缓存键值，值为空直接返回null；存在值时尝试反序列化，失败也降级为null。
     * @Param: query 用户投研问题。
     * @Return: 命中时返回缓存推荐结果；未命中或解析失败返回null。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
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

    /**
     * @Description: 将同步推荐结果写入Redis并设置TTL；缓存组件不可用或写入失败时静默降级，不影响推荐返回。
     * @Logic: 获取Redis模板后将序列化结果写入带TTL键值；任一异常被吞掉以保证推荐主流程可用。
     * @Param: query 用户投研问题；response 同步推荐结果对象。
     * @Return: 无（仅缓存副作用）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
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

    /**
     * @Description: 基于query生成推荐缓存键，统一前缀并去除首尾空白，避免同义请求产生重复键。
     * @Logic: 将业务固定前缀与去空白后的query拼接，生成稳定缓存键。
     * @Param: query 用户投研问题。
     * @Return: 推荐缓存键字符串。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private String cacheKey(String query) {
        return "ai-report:recommend:" + query.trim();
    }

    private record RecommendationContext(
            List<TopResultRespDTO> topResults,
            String evidence
    ) {
    }
}
