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
        // 保存 Redis 提供器，缓存不可用时允许推荐链路继续执行。
        this.redisTemplateProvider = redisTemplateProvider;
        // 保存 Qwen 客户端，用于后续同步结构化生成和流式生成。
        this.qwenClient = qwenClient;
        // 保存 Prompt 模板服务，用于加载推荐和流式推荐 Prompt。
        this.promptTemplateService = promptTemplateService;
        // 保存召回服务，用于根据 query 获取研报证据。
        this.reportRetrievalService = reportRetrievalService;
        // 保存输入意图服务，用于召回前判断 query 是否可分析。
        this.researchQueryIntentService = researchQueryIntentService;
        // 保存证据护栏服务，用于召回后判断输出等级和降级原因。
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
        // 先尝试读取同步推荐缓存，减少相同 query 的重复召回和模型调用。
        RecommendRespDTO cached = getCached(query);
        // 缓存命中时直接返回历史推荐结果。
        if (cached != null) {
            // 返回缓存响应，不再进入输入判定、召回或模型生成。
            return cached;
        }

        // 构建推荐上下文，内部会完成输入判定、召回、证据降级和证据文本拼接。
        RecommendationContext context = buildRecommendationContext(query);
        // 基于上下文输出等级构建同步响应，必要时调用模型或返回降级模板。
        RecommendRespDTO response = buildSynchronousResponse(query, context);
        // 将同步响应写入缓存，缓存失败不影响当前返回。
        cache(query, response);
        // 返回最终同步推荐响应。
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
        // defer 确保每次订阅流时都重新执行输入判定、召回和生成链路。
        return Flux.defer(() -> {
            // 构建流式推荐上下文，包含输入意图、召回证据和输出等级。
            RecommendationContext context = buildRecommendationContext(query);
            // L0 表示输入不可分析，必须在召回前短路并返回投研模式引导。
            if (RecommendationOutputLevelEnum.L0_REJECT.equals(context.outputLevel())) {
                // 返回固定的 guardrail/evidence/delta/done 事件序列，保证前端 loading 正常结束。
                return Flux.just(
                        // 推送护栏状态事件，说明输入不可分析。
                        statusEvent(STAGE_GUARDRAIL, "输入不可分析", context),
                        // 推送空证据事件，明确该请求未进入召回。
                        event(EVENT_EVIDENCE, streamData(STAGE_GUARDRAIL, "未进入召回", null, List.of(), context)),
                        // 推送投研模式引导语作为正文增量。
                        deltaEvent(RESEARCH_GUIDANCE_MESSAGE, context),
                        // 推送 done 事件结束 SSE 流。
                        doneEvent()
                );
            }
            // 非 L0 输入先发送检索完成和生成开始等头部事件。
            Flux<ServerSentEvent<RecommendStreamEventDTO>> head = Flux.just(
                    // 通知前端当前处于证据检索阶段。
                    statusEvent(STAGE_RETRIEVING, "正在检索相关研报证据", context),
                    // 将召回 Top 结果和证据质量元数据发送给前端。
                    event(EVENT_EVIDENCE, streamData(STAGE_RETRIEVING, "检索完成", null, context.topResults(), context)),
                    // 通知前端接下来进入模型生成或降级文案输出阶段。
                    statusEvent(STAGE_GENERATING, "正在生成推荐分析", context)
            );
            // 拼接头部事件、模型增量事件和完成事件，形成完整 SSE 流。
            return Flux.concat(head, streamLlmRecommendation(query, context), Flux.just(doneEvent()));
        // 任意运行时异常都转成 error + done，避免前端流悬挂。
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
        // L1 表示证据不足或污染，不能调用模型生成高确定性分析，直接输出降级说明。
        if (RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED.equals(context.outputLevel())) {
            // 将 L1 降级说明包装为 delta 事件返回。
            return Flux.just(deltaEvent(buildL1Recommendation(context), context));
        }
        // 没有 Top 证据时输出固定证据缺失提示。
        if (context.topResults().isEmpty()) {
            // 返回证据缺失提示，避免模型在无证据情况下编造。
            return Flux.just(deltaEvent(EVIDENCE_MISSING_MESSAGE, context));
        }
        // 加载流式推荐 system Prompt。
        String systemPrompt = promptTemplateService.loadTemplate("prompts/recommend-stream-system-prompt.txt");
        // 渲染流式推荐 user Prompt，注入 query、证据文本和护栏上下文。
        String userPrompt = promptTemplateService.render("prompts/recommend-stream-user-prompt.txt",
                Map.of("query", query, "evidence", context.evidence(), "guardrail", buildGuardrailPromptContext(context)));
        // 调用 Qwen 流式接口，并把模型增量转换为 SSE delta 事件。
        return qwenClient.chatStream(systemPrompt, userPrompt)
                // 过滤空增量，避免前端收到无意义 delta。
                .filter(content -> content != null && !content.isBlank())
                // 将模型输出片段包装为携带护栏元数据的 delta 事件。
                .map(content -> deltaEvent(content, context))
                // 模型未配置或无输出时返回固定降级提示。
                .switchIfEmpty(Flux.just(deltaEvent(MODEL_MISSING_MESSAGE, context)))
                // 模型调用异常时返回 error 事件，不中断 SSE 结构。
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
        // 加载同步推荐 system Prompt。
        String systemPrompt = promptTemplateService.loadTemplate("prompts/recommend-system-prompt.txt");
        // 渲染同步推荐 user Prompt，注入 query、证据文本和护栏约束。
        String userPrompt = promptTemplateService.render("prompts/recommend-user-prompt.txt",
                Map.of("query", query, "evidence", context.evidence(), "guardrail", buildGuardrailPromptContext(context)));
        // 调用 Qwen 结构化输出接口，期望返回 LlmRecommendRespDTO。
        LlmRecommendRespDTO respDTO = qwenClient.chatForEntity(systemPrompt, userPrompt, LlmRecommendRespDTO.class);
        // 模型未配置或无返回时生成固定兜底响应。
        if (respDTO == null) {
            // 返回“仅检索证据”的降级结果，避免同步接口空指针。
            return new LlmRecommendRespDTO(
                    // analysis 字段说明当前没有模型分析。
                    "未配置通义千问模型，当前仅返回检索结果。",
                    // recommendation 字段提示用户配置模型后再启用 AI 推荐。
                    "请配置 DashScope 兼容 ChatModel 后启用 AI 推荐。",
                    // risks 字段写入模型未配置原因。
                    List.of("模型未配置"),
                    // citations 字段为空，表示没有模型引用。
                    List.of()
            );
        }
        // 返回模型结构化输出。
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
        // 对用户输入做投研意图判定，决定是否允许进入召回。
        QueryIntentDecision intentDecision = researchQueryIntentService.classify(query);
        // REJECT 表示输入不可分析，召回前短路。
        if (QueryIntentEnum.REJECT.equals(intentDecision.intent())) {
            // 构造 L0 证据决策，标记输入被拒绝且没有召回证据。
            EvidenceDecision evidenceDecision = new EvidenceDecision(
                    // L0 输出等级只允许返回投研模式引导语。
                    RecommendationOutputLevelEnum.L0_REJECT,
                    // 证据质量全部为 false，并写入 INPUT_REJECTED 问题码。
                    new RecommendRespDTO.EvidenceQualityRespDTO(false, false, false, false, false, List.of("INPUT_REJECTED")),
                    // 降级原因记录输入被拒绝。
                    List.of("INPUT_REJECTED"),
                    // L0 没有相关主题词。
                    java.util.Set.of()
            );
            // 返回空证据上下文，确保后续不会调用召回或模型生成完整分析。
            return new RecommendationContext(intentDecision, evidenceDecision, List.of(), "");
        }
        // 对可分析 query 执行研报召回，使用规范化 query 作为检索输入。
        List<ReportRetrievalService.RetrievedChunk> retrievedChunks = reportRetrievalService.retrieve(intentDecision.normalizedQuery());
        // 对召回结果做输出前证据质量评估，得到 L1/L2/L3 输出等级。
        EvidenceDecision evidenceDecision = evidenceGuardrailService.evaluate(intentDecision.normalizedQuery(), intentDecision.intent(), retrievedChunks);
        // 初始化前端展示 Top 结果列表。
        List<TopResultRespDTO> topResults = new ArrayList<>();
        // 初始化注入 Prompt 的证据文本。
        StringBuilder evidenceBuilder = new StringBuilder();

        // 主题覆盖不通过时，不展示低相关 Top 结果，避免前端误以为这些是有效证据。
        if (evidenceDecision.degradationReasons().contains("LOW_THEME_COVERAGE")) {
            // 返回空证据上下文，让上层输出主题覆盖不足的 L1 降级说明。
            return new RecommendationContext(intentDecision, evidenceDecision, List.of(), "");
        }

        // 遍历召回结果，组装前端 TopResult 和模型证据文本。
        for (int i = 0; i < retrievedChunks.size(); i++) {
            // 获取当前召回 chunk。
            ReportRetrievalService.RetrievedChunk retrievedChunk = retrievedChunks.get(i);
            // 读取当前 chunk 的原始 Document 和 metadata。
            Document doc = retrievedChunk.document();
            // 从 metadata 读取研报标题，缺失时使用 unknown。
            String title = String.valueOf(doc.getMetadata().getOrDefault("title", "unknown"));
            // 从 metadata 读取来源，缺失时使用 unknown。
            String source = String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"));
            // 从 metadata 读取章节路径，供前端定位证据。
            String sectionPath = String.valueOf(doc.getMetadata().getOrDefault("sectionPath", ""));
            // 从 metadata 读取子切片 uid，供排查召回命中。
            String chunkUid = String.valueOf(doc.getMetadata().getOrDefault("chunkUid", ""));
            // 从 metadata 读取父切片 uid，供排查父子上下文关系。
            String parentChunkUid = String.valueOf(doc.getMetadata().getOrDefault("parentChunkUid", ""));
            // 组装前端展示用 TopResult，chunkText 是命中子切片，parentContext/evidenceText 是父上下文。
            topResults.add(new TopResultRespDTO(retrievedChunk.score(), title, retrievedChunk.chunkText(), source,
                    // 写入切片定位字段。
                    sectionPath, chunkUid, parentChunkUid, retrievedChunk.evidenceText(),
                    // 写入主题、行业、公司和代码 metadata 摘要。
                    metadataList(doc, "themeCodes"), metadataList(doc, "industryCodes"),
                    // 写入公司名和股票代码摘要，并标记不是诊断候选。
                    metadataList(doc, "companyNames"), metadataList(doc, "tickers"), false));
            // 为模型证据文本追加 chunk 编号和来源信息。
            evidenceBuilder.append("[Chunk ").append(i + 1).append("] ")
                    // 追加研报标题。
                    .append("title=").append(title)
                    // 追加研报来源。
                    .append(", source=").append(source)
                    // 追加章节路径。
                    .append(", section=").append(sectionPath)
                    // 换行后开始写入证据正文。
                    .append("\n")
                    // 追加父上下文或子切片兜底文本。
                    .append(retrievedChunk.evidenceText())
                    // 每个 chunk 后保留空行，便于模型区分证据段。
                    .append("\n\n");
        }
        // 返回完整推荐上下文，供同步和流式链路复用。
        return new RecommendationContext(intentDecision, evidenceDecision, topResults, evidenceBuilder.toString());
    }

    /**
     * @Description: 按输出等级构建同步推荐响应。
     * @Logic: L0返回投研模式引导，L1返回证据降级说明，L2/L3调用模型后必要时再执行禁用推荐语保护。
     * @Param: query 用户投研问题；context 推荐上下文。
     * @Return: 推荐响应对象，包含输出等级和降级原因。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private RecommendRespDTO buildSynchronousResponse(String query, RecommendationContext context) {
        // L0 表示输入不可分析，直接返回投研模式引导，不展示召回证据。
        if (RecommendationOutputLevelEnum.L0_REJECT.equals(context.outputLevel())) {
            // 构造不可分析输入响应。
            return response(query, context, "输入不属于可分析投研问题。", RESEARCH_GUIDANCE_MESSAGE, List.of("未进入召回"), List.of());
        }
        // L1 表示召回证据缺失、低相关或污染，返回降级说明而不调用模型完整分析。
        if (RecommendationOutputLevelEnum.L1_INSUFFICIENT_OR_POLLUTED.equals(context.outputLevel())) {
            // 构造证据不足响应，风险字段承载降级原因。
            return response(query, context, "召回证据未通过输出前质量校验。", buildL1Recommendation(context), context.degradationReasons(), List.of());
        }
        // L2/L3 才调用模型生成结构化分析。
        LlmRecommendRespDTO llm = generateLlmRecommendation(query, context);
        // L2 或其他受限等级如果出现禁用推荐语，需要替换推荐结论。
        if (requiresOutputProtection(context, llm)) {
            // 使用受限推荐文案替换模型 recommendation，引用保留模型可用引用。
            return response(query, context, llm.analysis(), buildRestrictedRecommendation(context), List.of("降级输出禁止高确定性投资建议"), safeList(llm.citations()));
        }
        // 模型输出未触发保护时，按原始结构化结果返回。
        return response(query, context, llm.analysis(), llm.recommendation(), safeList(llm.risks()), safeList(llm.citations()));
    }

    /**
     * @Description: 统一封装同步推荐响应对象。
     * @Logic: 将模型正文、风险、引用和防护元数据组装成RecommendRespDTO，保证同步接口返回结构一致。
     * @Param: query 用户投研问题；context 推荐上下文；analysis 分析正文；recommendation 推荐或降级说明；risks 风险列表；citations 引用列表。
     * @Return: 推荐响应对象。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private RecommendRespDTO response(String query,
                                      RecommendationContext context,
                                      String analysis,
                                      String recommendation,
                                      List<String> risks,
                                      List<String> citations) {
        // 组装统一同步推荐响应，保证正常输出和降级输出字段结构一致。
        return new RecommendRespDTO(
                // 返回用户原始 query，便于前端回显。
                query,
                // 返回前端展示 Top 证据。
                context.topResults(),
                // 返回分析正文。
                analysis,
                // 返回推荐结论或降级说明。
                recommendation,
                // 风险列表做 null 兜底。
                safeList(risks),
                // 引用列表做 null 兜底。
                safeList(citations),
                // 返回输入意图名称。
                context.intentDecision().intent().name(),
                // 返回输入意图置信度。
                context.intentDecision().confidence(),
                // 返回输出等级名称。
                context.outputLevel().name(),
                // 返回降级原因列表。
                context.degradationReasons(),
                // 返回证据质量详情。
                context.evidenceQuality()
        );
    }

    /**
     * @Description: 判断模型输出是否触发高确定性推荐语保护。
     * @Logic: L3完整分析不拦截；L0/L1/L2扫描分析、推荐和风险文本中的禁用投资建议表达。
     * @Param: context 推荐上下文；llm 模型结构化输出。
     * @Return: 需要替换为受限推荐文案时返回true。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private boolean requiresOutputProtection(RecommendationContext context, LlmRecommendRespDTO llm) {
        // L3 是完整分析等级，允许模型输出正常投研结论。
        if (RecommendationOutputLevelEnum.L3_FULL_ANALYSIS.equals(context.outputLevel())) {
            // 完整分析不触发禁用推荐语保护。
            return false;
        }
        // 合并 analysis、recommendation、risks 文本，统一扫描禁用短语。
        String text = String.join("\n", safeText(llm.analysis()), safeText(llm.recommendation()), String.join("\n", safeList(llm.risks())));
        // 委托证据护栏服务检查是否包含买入、目标价、仓位等高确定性建议。
        return evidenceGuardrailService.containsForbiddenRecommendation(text);
    }

    /**
     * @Description: 构建L1证据不足或污染时的降级推荐说明。
     * @Logic: 无证据时返回固定证据缺失提示；其他情况拼接降级原因并提示用户补充或收敛问题。
     * @Param: context 推荐上下文。
     * @Return: L1降级推荐文本。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private String buildL1Recommendation(RecommendationContext context) {
        // 完全无证据时返回固定缺失提示。
        if (context.degradationReasons().contains("EVIDENCE_MISSING")) {
            // 告诉用户当前无法基于研报内容生成可靠推荐。
            return EVIDENCE_MISSING_MESSAGE;
        }
        // 主题覆盖不足时给出主题锚点未匹配的专门提示。
        if (context.degradationReasons().contains("LOW_THEME_COVERAGE")) {
            // 引导用户补充行业、公司或股票代码以收敛检索范围。
            return "未检索到与当前主题锚点匹配的研报证据，无法基于现有召回结果生成可靠主题研究结论。请补充更明确的行业、公司或股票代码后重新分析。";
        }
        // 其他证据污染或一致性问题拼接具体降级原因。
        return "当前召回证据不足或存在污染，无法支撑完整投研结论。降级原因："
                // 将问题码用中文顿号连接，便于用户理解为什么降级。
                + String.join("、", context.degradationReasons())
                // 补充下一步输入建议。
                + "。请补充公司名、股票代码，或收敛行业/主题后重新分析。";
    }

    /**
     * @Description: 构建受限输出等级下的推荐替代文案。
     * @Logic: L2主题研究允许给出研究对象或关注清单，但禁止买卖、评级、目标价和仓位建议。
     * @Param: context 推荐上下文。
     * @Return: 受限推荐文本。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private String buildRestrictedRecommendation(RecommendationContext context) {
        // L2 主题研究允许列研究对象，但禁止买卖评级和仓位目标价。
        if (RecommendationOutputLevelEnum.L2_THEME_RESEARCH.equals(context.outputLevel())) {
            // 返回主题研究专用受限文案。
            return "当前为主题研究输出，可将相关公司作为研究对象或关注清单；暂不提供买卖、评级、目标价或仓位建议。";
        }
        // 非 L2 的受限场景复用 L1 降级说明。
        return buildL1Recommendation(context);
    }

    /**
     * @Description: 构建注入推荐Prompt的防护上下文。
     * @Logic: 将输入意图、置信度、输出等级、降级原因和证据质量序列化为模型可读约束。
     * @Param: context 推荐上下文。
     * @Return: Prompt中的guardrail变量文本。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private String buildGuardrailPromptContext(RecommendationContext context) {
        // 拼接输入意图，提示模型当前 query 分类结果。
        return "inputIntent=" + context.intentDecision().intent()
                // 拼接输入置信度，提示模型规则或 LLM 分类可信度。
                + "\ninputConfidence=" + context.intentDecision().confidence()
                // 拼接输出等级，控制模型输出强度。
                + "\noutputLevel=" + context.outputLevel()
                // 拼接降级原因，要求模型围绕这些原因解释或收敛输出。
                + "\ndegradationReasons=" + String.join(",", context.degradationReasons())
                // 拼接证据质量 JSON，给模型提供结构化约束。
                + "\nevidenceQuality=" + JSON.toJSONString(context.evidenceQuality())
                // 明确不同等级允许和禁止的输出边界。
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
        // 构建不携带护栏元数据的兼容状态事件。
        return event(EVENT_STATUS, new RecommendStreamEventDTO(stage, message, null, List.of()));
    }

    /**
     * @Description: 构建带防护元数据的阶段状态事件。
     * @Logic: 通过streamData把输入意图、输出等级、降级原因和证据质量一起写入status事件。
     * @Param: stage 阶段标识；message 阶段说明文本；context 推荐上下文。
     * @Return: status事件对象。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private ServerSentEvent<RecommendStreamEventDTO> statusEvent(String stage, String message, RecommendationContext context) {
        // 构建携带输入意图、输出等级和证据质量的状态事件。
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
        // 构建不携带护栏元数据的兼容文本增量事件。
        return event(EVENT_DELTA, new RecommendStreamEventDTO(STAGE_GENERATING, null, text, List.of()));
    }

    /**
     * @Description: 构建带防护元数据的模型文本增量事件。
     * @Logic: 将文本片段和推荐上下文写入delta事件，便于前端同步展示输出等级和降级原因。
     * @Param: text 模型输出的增量文本片段；context 推荐上下文。
     * @Return: delta事件对象。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private ServerSentEvent<RecommendStreamEventDTO> deltaEvent(String text, RecommendationContext context) {
        // 构建携带护栏元数据的文本增量事件。
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
        // 构建 completed 阶段的 done 事件，前端据此停止 loading。
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
        // 错误消息为空时使用固定兜底文案，避免前端展示空错误。
        String safeMessage = message == null || message.isBlank() ? "推荐流式输出失败" : message;
        // 构建 error 阶段事件并携带安全错误消息。
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
        // 使用 Spring SSE builder 包装事件数据。
        return ServerSentEvent.<RecommendStreamEventDTO>builder(data)
                // 设置 SSE event 名称，前端按该字段区分状态、证据、增量、完成和错误。
                .event(eventName)
                // 构建最终 SSE 事件对象。
                .build();
    }

    /**
     * @Description: 构建流式事件负载。
     * @Logic: 在阶段文本、模型增量和Top5证据之外，统一附加输入意图、输出等级、降级原因和证据质量。
     * @Param: stage 阶段标识；message 阶段说明；text 模型增量文本；top5 检索证据；context 推荐上下文。
     * @Return: 流式事件负载对象。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private RecommendStreamEventDTO streamData(String stage,
                                               String message,
                                               String text,
                                               List<TopResultRespDTO> top5,
                                               RecommendationContext context) {
        // 统一组装流式事件数据，确保每类事件都能携带护栏元数据。
        return new RecommendStreamEventDTO(
                // 写入当前阶段标识。
                stage,
                // 写入阶段说明消息。
                message,
                // 写入模型增量文本。
                text,
                // 写入当前事件携带的 Top 证据。
                top5,
                // 写入输入意图名称。
                context.intentDecision().intent().name(),
                // 写入输出等级名称。
                context.outputLevel().name(),
                // 写入降级原因列表。
                context.degradationReasons(),
                // 写入证据质量详情。
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
        // 从 Spring 容器中获取 Redis 模板；未配置时缓存能力自动关闭。
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        // Redis 不可用时返回 null，让主流程继续召回和生成。
        if (redis == null) {
            // null 表示缓存未命中或不可用。
            return null;
        }
        // 读取 query 对应的缓存 JSON。
        String value = redis.opsForValue().get(cacheKey(query));
        // 缓存为空或空白时视为未命中。
        if (value == null || value.isBlank()) {
            // 返回 null 触发后续实时推荐。
            return null;
        }
        // 尝试解析缓存 JSON。
        try {
            // 反序列化为同步推荐响应对象。
            return JSON.parseObject(value, RecommendRespDTO.class);
        } catch (Exception e) {
            // 缓存内容异常时不阻塞主链路，按未命中处理。
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
        // 从 Spring 容器中获取 Redis 模板；未配置时跳过缓存写入。
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        // Redis 不可用时直接返回，保证推荐结果仍然可以返回给用户。
        if (redis == null) {
            // 无缓存副作用。
            return;
        }
        // 尝试写入缓存。
        try {
            // 将响应序列化为 JSON 并设置 TTL，避免缓存永久占用。
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
        // 使用固定业务前缀隔离推荐缓存，并对 query 去除首尾空白。
        return "ai-report:recommend:" + query.trim();
    }

    /**
     * @Description: 将可能为空的字符串列表标准化为空列表。
     * @Logic: 避免响应组装和文本拼接时因null列表触发空指针异常。
     * @Param: values 原始字符串列表。
     * @Return: 非null字符串列表。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private List<String> safeList(List<String> values) {
        // null 列表统一转换为空列表，避免响应构造和文本拼接空指针。
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
        // 从召回 Document metadata 中读取指定字段值。
        Object value = document.getMetadata().get(key);
        // metadata 字段为集合时逐项转为字符串列表。
        if (value instanceof Iterable<?> iterable) {
            // 初始化输出列表，过滤空元素。
            List<String> values = new ArrayList<>();
            // 遍历集合值。
            for (Object item : iterable) {
                // 非空且非空白的元素才进入展示摘要。
                if (item != null && !String.valueOf(item).isBlank()) {
                    // 将元素转为字符串后加入结果。
                    values.add(String.valueOf(item));
                }
            }
            // 返回集合型 metadata 的字符串化结果。
            return values;
        }
        // metadata 字段为单值且非空时返回单元素列表。
        if (value != null && !String.valueOf(value).isBlank()) {
            // 返回单值字段的字符串列表。
            return List.of(String.valueOf(value));
        }
        // 字段不存在或为空时返回空列表。
        return List.of();
    }

    /**
     * @Description: 将可能为空的文本标准化为空字符串。
     * @Logic: 避免拼接模型输出时因null文本触发空指针异常。
     * @Param: text 原始文本。
     * @Return: 非null文本。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    private String safeText(String text) {
        // null 文本统一转为空字符串，其余文本原样返回。
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
            // 从证据决策中读取当前输出等级。
            return evidenceDecision.outputLevel();
        }

        /**
         * @Description: 读取当前推荐链路的降级原因列表。
         * @Logic: 从证据决策对象中透传degradationReasons，供响应、流式事件和Prompt共同使用。
         * @Return: 降级原因列表。
         */
        private List<String> degradationReasons() {
            // 从证据决策中读取降级原因列表。
            return evidenceDecision.degradationReasons();
        }

        /**
         * @Description: 读取当前推荐链路的证据质量详情。
         * @Logic: 从证据决策对象中透传quality，供前端展示和Prompt约束使用。
         * @Return: 证据质量响应对象。
         */
        private RecommendRespDTO.EvidenceQualityRespDTO evidenceQuality() {
            // 从证据决策中读取证据质量详情。
            return evidenceDecision.quality();
        }
    }
}
