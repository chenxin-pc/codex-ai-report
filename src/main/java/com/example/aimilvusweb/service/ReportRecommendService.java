package com.example.aimilvusweb.service;

import com.alibaba.fastjson2.JSON;
import com.example.aimilvusweb.common.llm.QwenClient;
import com.example.aimilvusweb.common.prompt.PromptTemplateService;
import com.example.aimilvusweb.dto.LlmRecommendRespDTO;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.dto.RecommendStreamEventDTO;
import com.example.aimilvusweb.dto.TopResultRespDTO;
import com.example.aimilvusweb.enums.QueryIntentEnum;
import com.example.aimilvusweb.enums.RecommendationOutputLevelEnum;
import com.example.aimilvusweb.service.RecommendationEvidenceGuardrailService.EvidenceDecision;
import com.example.aimilvusweb.service.ResearchQueryIntentService.QueryIntentDecision;
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

/**
 * @Description: 研报推荐编排服务，负责串联输入判定、证据召回、输出降级、LLM生成和缓存。
 * @Logic: 同步链路返回完整推荐结果，流式链路按阶段推送状态、证据、增量文本和完成事件。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
@Service
public class ReportRecommendService {

    /** 推荐结果缓存有效期，避免同一 query 短时间内重复召回和调用模型。 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    /** SSE 状态事件名，用于推送当前处理阶段。 */
    private static final String EVENT_STATUS = "status";
    /** SSE 证据事件名，用于推送召回Top结果。 */
    private static final String EVENT_EVIDENCE = "evidence";
    /** SSE 文本增量事件名，用于推送模型输出片段。 */
    private static final String EVENT_DELTA = "delta";
    /** SSE 完成事件名，用于通知前端流式响应结束。 */
    private static final String EVENT_DONE = "done";
    /** SSE 错误事件名，用于通知前端异常信息。 */
    private static final String EVENT_ERROR = "error";
    /** 检索阶段标识，表示正在或已经执行证据召回。 */
    private static final String STAGE_RETRIEVING = "retrieving";
    /** 生成阶段标识，表示正在或已经执行模型推荐生成。 */
    private static final String STAGE_GENERATING = "generating";
    /** 完成阶段标识，表示流式推荐链路已结束。 */
    private static final String STAGE_COMPLETED = "completed";
    /** 错误阶段标识，表示流式推荐链路异常终止。 */
    private static final String STAGE_ERROR = "error";
    /** 护栏阶段标识，表示输入被投研护栏短路处理。 */
    private static final String STAGE_GUARDRAIL = "guardrail";
    /** 模型未配置时的同步和流式降级提示。 */
    private static final String MODEL_MISSING_MESSAGE = "未配置通义千问模型，当前仅返回检索证据。";
    /** 证据缺失时的固定降级提示。 */
    private static final String EVIDENCE_MISSING_MESSAGE = "未检索到可用证据，无法基于研报内容生成可靠推荐。";
    /** 不可分析输入短路后返回给用户的投研模式引导语。 */
    private static final String RESEARCH_GUIDANCE_MESSAGE = "当前为投研分析模式。请提供公司名、股票代码，或一个明确的行业/主题研究问题。";

    /** Redis模板提供器，允许缓存组件缺失时推荐链路静默降级。 */
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    /** Qwen模型客户端，用于同步结构化输出和流式文本生成。 */
    private final QwenClient qwenClient;
    /** Prompt模板服务，用于加载和渲染推荐与意图判定Prompt。 */
    private final PromptTemplateService promptTemplateService;
    /** 研报召回服务，用于获取与用户query相关的Top chunk证据。 */
    private final ReportRetrievalService reportRetrievalService;
    /** 输入意图判定服务，用于决定query是否进入投研召回链路。 */
    private final ResearchQueryIntentService researchQueryIntentService;
    /** 证据质量护栏服务，用于输出前降级和禁用投资建议语检查。 */
    private final RecommendationEvidenceGuardrailService evidenceGuardrailService;

    /**
     * @Description: 初始化ReportRecommendService依赖与运行所需组件。
     * @Logic: 保存缓存、模型调用、Prompt模板、检索服务、输入判定服务和证据降级服务依赖，供同步与流式推荐链路复用。
     * @Param: redisTemplateProvider Redis模板提供器；qwenClient 模型调用客户端；promptTemplateService Prompt模板服务；reportRetrievalService 检索服务；researchQueryIntentService 输入意图判定服务；evidenceGuardrailService 证据质量降级服务。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    public ReportRecommendService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                  QwenClient qwenClient,
                                  PromptTemplateService promptTemplateService,
                                  ReportRetrievalService reportRetrievalService,
                                  ResearchQueryIntentService researchQueryIntentService,
                                  RecommendationEvidenceGuardrailService evidenceGuardrailService) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.qwenClient = qwenClient;
        this.promptTemplateService = promptTemplateService;
        this.reportRetrievalService = reportRetrievalService;
        this.researchQueryIntentService = researchQueryIntentService;
        this.evidenceGuardrailService = evidenceGuardrailService;
    }

    /**
     * @Description: 生成推荐结果并返回推荐响应。
     * @Logic: 先查缓存命中并返回；未命中则构建输入意图与证据上下文，再按输出等级生成响应并写入缓存。
     * @Param: query 用户投研问题。
     * @Return: 结构化推荐结果对象，包含检索Top5、模型输出、输入意图、输出等级与降级原因。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    public RecommendRespDTO recommend(String query) {
        RecommendRespDTO cached = getCached(query);
        if (cached != null) {
            return cached;
        }

        RecommendationContext context = buildRecommendationContext(query);
        RecommendRespDTO response = buildSynchronousResponse(query, context);
        cache(query, response);
        return response;
    }

    /**
     * @Description: 生成推荐结果并以 SSE 事件流返回状态、证据和模型增量。
     * @Logic: 先构建输入意图与证据上下文；不可分析输入短路返回引导语，其余输入发送status/evidence阶段事件后拼接模型增量流。
     * @Param: query 用户投研问题。
     * @Return: SSE事件流，按event区分status、evidence、delta、done和error，并携带防护元数据。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    public Flux<ServerSentEvent<RecommendStreamEventDTO>> recommendStream(String query) {
        return Flux.defer(() -> {
            RecommendationContext context = buildRecommendationContext(query);
            if (RecommendationOutputLevelEnum.L0_REJECT.equals(context.outputLevel())) {
                return Flux.just(
                        statusEvent(STAGE_GUARDRAIL, "输入不可分析", context),
                        event(EVENT_EVIDENCE, streamData(STAGE_GUARDRAIL, "未进入召回", null, List.of(), context)),
                        deltaEvent(RESEARCH_GUIDANCE_MESSAGE, context),
                        doneEvent()
                );
            }
            Flux<ServerSentEvent<RecommendStreamEventDTO>> head = Flux.just(
                    statusEvent(STAGE_RETRIEVING, "正在检索相关研报证据", context),
                    event(EVENT_EVIDENCE, streamData(STAGE_RETRIEVING, "检索完成", null, context.topResults(), context)),
                    statusEvent(STAGE_GENERATING, "正在生成推荐分析", context)
            );
            return Flux.concat(head, streamLlmRecommendation(query, context), Flux.just(doneEvent()));
        }).onErrorResume(e -> Flux.just(errorEvent(e.getMessage()), doneEvent()));
    }

    /**
     * @Description: 基于query与防护上下文生成流式推荐事件。
     * @Logic: L1降级直接输出证据不足提示；其余等级加载流式Prompt并注入guardrail上下文，模型无增量时返回模型未配置提示。
     * @Param: query 用户投研问题；context 推荐上下文，包含输入意图、证据质量、Top5与拼接证据文本。
     * @Return: 模型阶段SSE事件流，事件主要为delta，异常时为error。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private Flux<ServerSentEvent<RecommendStreamEventDTO>> streamLlmRecommendation(String query, RecommendationContext context) {
        if (RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED.equals(context.outputLevel())) {
            return Flux.just(deltaEvent(buildL1Recommendation(context), context));
        }
        if (context.topResults().isEmpty()) {
            return Flux.just(deltaEvent(EVIDENCE_MISSING_MESSAGE, context));
        }
        String systemPrompt = promptTemplateService.loadTemplate("prompts/recommend-stream-system-prompt.txt");
        String userPrompt = promptTemplateService.render("prompts/recommend-stream-user-prompt.txt",
                Map.of("query", query, "evidence", context.evidence(), "guardrail", buildGuardrailPromptContext(context)));
        return qwenClient.chatStream(systemPrompt, userPrompt)
                .filter(content -> content != null && !content.isBlank())
                .map(content -> deltaEvent(content, context))
                .switchIfEmpty(Flux.just(deltaEvent(MODEL_MISSING_MESSAGE, context)))
                .onErrorResume(e -> Flux.just(errorEvent(e.getMessage())));
    }

    /**
     * @Description: 使用同步推荐Prompt生成结构化推荐结果；输入query和证据文本，输出LlmRecommendRespDTO，模型未配置时返回默认降级文案。
     * @Logic: 加载同步推荐Prompt并渲染query、evidence和guardrail变量后调用结构化模型输出；当模型客户端不可用时返回固定降级结果。
     * @Param: query 用户投研问题；context 推荐上下文，提供证据文本和输出等级约束。
     * @Return: 结构化推荐DTO，包含analysis、recommendation、risks与citations。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private LlmRecommendRespDTO generateLlmRecommendation(String query, RecommendationContext context) {
        String systemPrompt = promptTemplateService.loadTemplate("prompts/recommend-system-prompt.txt");
        String userPrompt = promptTemplateService.render("prompts/recommend-user-prompt.txt",
                Map.of("query", query, "evidence", context.evidence(), "guardrail", buildGuardrailPromptContext(context)));
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
     * @Description: 构建推荐所需的输入意图、召回结果、证据质量决策和证据文本。
     * @Logic: 先做输入可分析性判定；不可分析输入不进入召回，其余输入召回chunk后执行输出前证据降级判断。
     * @Param: query 用户投研问题。
     * @Return: 推荐上下文对象，包含输入意图、证据决策、Top5列表与拼接后的证据文本。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private RecommendationContext buildRecommendationContext(String query) {
        QueryIntentDecision intentDecision = researchQueryIntentService.classify(query);
        if (QueryIntentEnum.REJECT.equals(intentDecision.intent())) {
            EvidenceDecision evidenceDecision = new EvidenceDecision(
                    RecommendationOutputLevelEnum.L0_REJECT,
                    new RecommendRespDTO.EvidenceQualityRespDTO(false, false, false, false, false, List.of("INPUT_REJECTED")),
                    List.of("INPUT_REJECTED"),
                    java.util.Set.of()
            );
            return new RecommendationContext(intentDecision, evidenceDecision, List.of(), "");
        }
        List<ReportRetrievalService.RetrievedChunk> retrievedChunks = reportRetrievalService.retrieve(intentDecision.normalizedQuery());
        EvidenceDecision evidenceDecision = evidenceGuardrailService.evaluate(intentDecision.normalizedQuery(), intentDecision.intent(), retrievedChunks);
        List<TopResultRespDTO> topResults = new ArrayList<>();
        StringBuilder evidenceBuilder = new StringBuilder();

        if (evidenceDecision.degradationReasons().contains("LOW_THEME_COVERAGE")) {
            return new RecommendationContext(intentDecision, evidenceDecision, List.of(), "");
        }

        for (int i = 0; i < retrievedChunks.size(); i++) {
            ReportRetrievalService.RetrievedChunk retrievedChunk = retrievedChunks.get(i);
            Document doc = retrievedChunk.document();
            String title = String.valueOf(doc.getMetadata().getOrDefault("title", "unknown"));
            String source = String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"));
            String sectionPath = String.valueOf(doc.getMetadata().getOrDefault("sectionPath", ""));
            String chunkUid = String.valueOf(doc.getMetadata().getOrDefault("chunkUid", ""));
            String parentChunkUid = String.valueOf(doc.getMetadata().getOrDefault("parentChunkUid", ""));
            topResults.add(new TopResultRespDTO(retrievedChunk.score(), title, retrievedChunk.chunkText(), source,
                    sectionPath, chunkUid, parentChunkUid, retrievedChunk.evidenceText(),
                    metadataList(doc, "themeCodes"), metadataList(doc, "industryCodes"),
                    metadataList(doc, "companyNames"), metadataList(doc, "tickers"), false));
            evidenceBuilder.append("[Chunk ").append(i + 1).append("] ")
                    .append("title=").append(title)
                    .append(", source=").append(source)
                    .append(", section=").append(sectionPath)
                    .append("\n")
                    .append(retrievedChunk.evidenceText())
                    .append("\n\n");
        }
        return new RecommendationContext(intentDecision, evidenceDecision, topResults, evidenceBuilder.toString());
    }

    /**
     * @Description: 按输出等级构建同步推荐响应。
     * @Logic: L0返回投研模式引导，L1返回证据降级说明，L2/L3调用模型后必要时再执行禁用推荐语保护。
     * @Param: query 用户投研问题；context 推荐上下文。
     * @Return: 推荐响应对象，包含输出等级和降级原因。
     */
    private RecommendRespDTO buildSynchronousResponse(String query, RecommendationContext context) {
        if (RecommendationOutputLevelEnum.L0_REJECT.equals(context.outputLevel())) {
            return response(query, context, "输入不属于可分析投研问题。", RESEARCH_GUIDANCE_MESSAGE, List.of("未进入召回"), List.of());
        }
        if (RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED.equals(context.outputLevel())) {
            return response(query, context, "召回证据未通过输出前质量校验。", buildL1Recommendation(context), context.degradationReasons(), List.of());
        }
        LlmRecommendRespDTO llm = generateLlmRecommendation(query, context);
        if (requiresOutputProtection(context, llm)) {
            return response(query, context, llm.analysis(), buildRestrictedRecommendation(context), List.of("降级输出禁止高确定性投资建议"), safeList(llm.citations()));
        }
        return response(query, context, llm.analysis(), llm.recommendation(), safeList(llm.risks()), safeList(llm.citations()));
    }

    /**
     * @Description: 统一封装同步推荐响应对象。
     * @Logic: 将模型正文、风险、引用和防护元数据组装成RecommendRespDTO，保证同步接口返回结构一致。
     * @Param: query 用户投研问题；context 推荐上下文；analysis 分析正文；recommendation 推荐或降级说明；risks 风险列表；citations 引用列表。
     * @Return: 推荐响应对象。
     */
    private RecommendRespDTO response(String query,
                                      RecommendationContext context,
                                      String analysis,
                                      String recommendation,
                                      List<String> risks,
                                      List<String> citations) {
        return new RecommendRespDTO(
                query,
                context.topResults(),
                analysis,
                recommendation,
                safeList(risks),
                safeList(citations),
                context.intentDecision().intent().name(),
                context.intentDecision().confidence(),
                context.outputLevel().name(),
                context.degradationReasons(),
                context.evidenceQuality()
        );
    }

    /**
     * @Description: 判断模型输出是否触发高确定性推荐语保护。
     * @Logic: L3完整分析不拦截；L0/L1/L2扫描分析、推荐和风险文本中的禁用投资建议表达。
     * @Param: context 推荐上下文；llm 模型结构化输出。
     * @Return: 需要替换为受限推荐文案时返回true。
     */
    private boolean requiresOutputProtection(RecommendationContext context, LlmRecommendRespDTO llm) {
        if (RecommendationOutputLevelEnum.L3_FULL_ANALYSIS.equals(context.outputLevel())) {
            return false;
        }
        String text = String.join("\n", safeText(llm.analysis()), safeText(llm.recommendation()), String.join("\n", safeList(llm.risks())));
        return evidenceGuardrailService.containsForbiddenRecommendation(text);
    }

    /**
     * @Description: 构建L1证据不足或污染时的降级推荐说明。
     * @Logic: 无证据时返回固定证据缺失提示；其他情况拼接降级原因并提示用户补充或收敛问题。
     * @Param: context 推荐上下文。
     * @Return: L1降级推荐文本。
     */
    private String buildL1Recommendation(RecommendationContext context) {
        if (context.degradationReasons().contains("EVIDENCE_MISSING")) {
            return EVIDENCE_MISSING_MESSAGE;
        }
        if (context.degradationReasons().contains("LOW_THEME_COVERAGE")) {
            return "未检索到与当前主题锚点匹配的研报证据，无法基于现有召回结果生成可靠主题研究结论。请补充更明确的行业、公司或股票代码后重新分析。";
        }
        return "当前召回证据不足或存在污染，无法支撑完整投研结论。降级原因："
                + String.join("、", context.degradationReasons())
                + "。请补充公司名、股票代码，或收敛行业/主题后重新分析。";
    }

    /**
     * @Description: 构建受限输出等级下的推荐替代文案。
     * @Logic: L2主题研究允许给出研究对象或关注清单，但禁止买卖、评级、目标价和仓位建议。
     * @Param: context 推荐上下文。
     * @Return: 受限推荐文本。
     */
    private String buildRestrictedRecommendation(RecommendationContext context) {
        if (RecommendationOutputLevelEnum.L2_THEME_RESEARCH.equals(context.outputLevel())) {
            return "当前为主题研究输出，可将相关公司作为研究对象或关注清单；暂不提供买卖、评级、目标价或仓位建议。";
        }
        return buildL1Recommendation(context);
    }

    /**
     * @Description: 构建注入推荐Prompt的防护上下文。
     * @Logic: 将输入意图、置信度、输出等级、降级原因和证据质量序列化为模型可读约束。
     * @Param: context 推荐上下文。
     * @Return: Prompt中的guardrail变量文本。
     */
    private String buildGuardrailPromptContext(RecommendationContext context) {
        return "inputIntent=" + context.intentDecision().intent()
                + "\ninputConfidence=" + context.intentDecision().confidence()
                + "\noutputLevel=" + context.outputLevel()
                + "\ndegradationReasons=" + String.join(",", context.degradationReasons())
                + "\nevidenceQuality=" + JSON.toJSONString(context.evidenceQuality())
                + "\n要求：L1 仅说明证据不足或污染；L2 可推荐相关公司候选但禁止买卖、评级、目标价或仓位建议；L3 才可输出完整投研分析。";
    }

    /**
     * @Description: 构建阶段状态事件。
     * @Logic: 使用统一事件构造器包装阶段与消息，生成status类型SSE事件。
     * @Param: stage 阶段标识；message 阶段说明文本。
     * @Return: status事件对象。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private ServerSentEvent<RecommendStreamEventDTO> statusEvent(String stage, String message) {
        return event(EVENT_STATUS, new RecommendStreamEventDTO(stage, message, null, List.of()));
    }

    /**
     * @Description: 构建带防护元数据的阶段状态事件。
     * @Logic: 通过streamData把输入意图、输出等级、降级原因和证据质量一起写入status事件。
     * @Param: stage 阶段标识；message 阶段说明文本；context 推荐上下文。
     * @Return: status事件对象。
     */
    private ServerSentEvent<RecommendStreamEventDTO> statusEvent(String stage, String message, RecommendationContext context) {
        return event(EVENT_STATUS, streamData(stage, message, null, List.of(), context));
    }

    /**
     * @Description: 构建模型文本增量事件。
     * @Logic: 将模型增量文本写入事件数据并标记为delta事件。
     * @Param: text 模型输出的增量文本片段。
     * @Return: delta事件对象。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private ServerSentEvent<RecommendStreamEventDTO> deltaEvent(String text) {
        return event(EVENT_DELTA, new RecommendStreamEventDTO(STAGE_GENERATING, null, text, List.of()));
    }

    /**
     * @Description: 构建带防护元数据的模型文本增量事件。
     * @Logic: 将文本片段和推荐上下文写入delta事件，便于前端同步展示输出等级和降级原因。
     * @Param: text 模型输出的增量文本片段；context 推荐上下文。
     * @Return: delta事件对象。
     */
    private ServerSentEvent<RecommendStreamEventDTO> deltaEvent(String text, RecommendationContext context) {
        return event(EVENT_DELTA, streamData(STAGE_GENERATING, null, text, List.of(), context));
    }

    /**
     * @Description: 构建完成事件。
     * @Logic: 生成固定completed阶段的done事件，作为流式响应结束标记。
     * @Param: 无。
     * @Return: done事件对象。
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
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private ServerSentEvent<RecommendStreamEventDTO> event(String eventName, RecommendStreamEventDTO data) {
        return ServerSentEvent.<RecommendStreamEventDTO>builder(data)
                .event(eventName)
                .build();
    }

    /**
     * @Description: 构建流式事件负载。
     * @Logic: 在阶段文本、模型增量和Top5证据之外，统一附加输入意图、输出等级、降级原因和证据质量。
     * @Param: stage 阶段标识；message 阶段说明；text 模型增量文本；top5 检索证据；context 推荐上下文。
     * @Return: 流式事件负载对象。
     */
    private RecommendStreamEventDTO streamData(String stage,
                                               String message,
                                               String text,
                                               List<TopResultRespDTO> top5,
                                               RecommendationContext context) {
        return new RecommendStreamEventDTO(
                stage,
                message,
                text,
                top5,
                context.intentDecision().intent().name(),
                context.outputLevel().name(),
                context.degradationReasons(),
                context.evidenceQuality()
        );
    }

    /**
     * @Description: 按query读取Redis缓存推荐结果；缓存不存在、为空或反序列化失败时返回null，不抛出异常中断主流程。
     * @Logic: 获取Redis模板并读取缓存键值，值为空直接返回null；存在值时尝试反序列化，失败也降级为null。
     * @Param: query 用户投研问题。
     * @Return: 命中时返回缓存推荐结果；未命中或解析失败返回null。
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
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    private String cacheKey(String query) {
        return "ai-report:recommend:" + query.trim();
    }

    /**
     * @Description: 将可能为空的字符串列表标准化为空列表。
     * @Logic: 避免响应组装和文本拼接时因null列表触发空指针异常。
     * @Param: values 原始字符串列表。
     * @Return: 非null字符串列表。
     */
    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    /**
     * @Description: 从文档 metadata 中读取字符串列表。
     * @Logic: 兼容 List、数组和单值字符串；空值返回空列表，供 TopResult 展示标签摘要。
     * @Param: document 召回文档；key metadata 字段名。
     * @Return: 字符串列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private List<String> metadataList(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item));
                }
            }
            return values;
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            return List.of(String.valueOf(value));
        }
        return List.of();
    }

    /**
     * @Description: 将可能为空的文本标准化为空字符串。
     * @Logic: 避免拼接模型输出时因null文本触发空指针异常。
     * @Param: text 原始文本。
     * @Return: 非null文本。
     */
    private String safeText(String text) {
        return text == null ? "" : text;
    }

    /**
     * @Description: 推荐链路内部上下文，集中保存输入意图、证据决策、展示证据和模型证据文本。
     * @Logic: 作为同步和流式推荐的共享数据载体，避免在多个方法之间重复传递分散参数。
     * @Param: intentDecision 输入意图判定结果；evidenceDecision 证据质量与输出等级决策；topResults 前端展示Top5；evidence 模型使用的证据文本。
     */
    private record RecommendationContext(
            /** 输入意图判定结果，决定是否召回以及同步/流式输出的输入标签。 */
            QueryIntentDecision intentDecision,
            /** 证据质量决策，承载输出等级、降级原因和证据质量详情。 */
            EvidenceDecision evidenceDecision,
            /** 前端展示的Top召回结果列表。 */
            List<TopResultRespDTO> topResults,
            /** 注入推荐Prompt的拼接证据文本。 */
            String evidence
    ) {
        /**
         * @Description: 读取当前推荐链路的输出等级。
         * @Logic: 从证据决策对象中透传outputLevel，保持调用处表达简洁。
         * @Return: 推荐输出等级。
         */
        private RecommendationOutputLevelEnum outputLevel() {
            return evidenceDecision.outputLevel();
        }

        /**
         * @Description: 读取当前推荐链路的降级原因列表。
         * @Logic: 从证据决策对象中透传degradationReasons，供响应、流式事件和Prompt共同使用。
         * @Return: 降级原因列表。
         */
        private List<String> degradationReasons() {
            return evidenceDecision.degradationReasons();
        }

        /**
         * @Description: 读取当前推荐链路的证据质量详情。
         * @Logic: 从证据决策对象中透传quality，供前端展示和Prompt约束使用。
         * @Return: 证据质量响应对象。
         */
        private RecommendRespDTO.EvidenceQualityRespDTO evidenceQuality() {
            return evidenceDecision.quality();
        }
    }
}
