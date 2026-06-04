package com.example.aimilvusweb.service.taxonomy;

import com.example.aimilvusweb.infra.ai.QwenClient;
import com.example.aimilvusweb.infra.ai.PromptTemplateService;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.dto.LlmQueryIntentRespDTO;
import com.example.aimilvusweb.enums.QueryIntentEnum;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @Description: 投研输入意图服务，按规则优先、LLM兜底方式判断 query 是否进入投研链路。
 * @Logic: 高置信规则直接返回；规则低置信时调用结构化分类模型；模型低置信、异常或未配置时保守拒绝。
 * @author: cx
 * @Date: 2026-05-24 00:45:00
 */
@Service
public class ResearchQueryIntentService {

    /** 规则明确命中时使用的满分置信度。 */
    private static final double DIRECT_CONFIDENCE = 1.0D;
    /** 规则判定来源标识。 */
    private static final String RULE_SOURCE = "RULE";
    /** LLM兜底判定来源标识。 */
    private static final String LLM_SOURCE = "LLM";
    /** 规则和LLM都低置信时的保守兜底来源标识。 */
    private static final String FALLBACK_SOURCE = "FALLBACK";

    /** 本地词典服务，提供拒绝短语、投研动作、主题词、行业词和股票代码正则。 */
    private final QueryGuardrailDictionaryService dictionaryService;
    /** 研报质量配置，提供输入护栏开关和分类置信度阈值。 */
    private final ReportQualityProperties reportQualityProperties;
    /** Qwen模型客户端，用于规则低置信时执行结构化意图兜底分类。 */
    private final QwenClient qwenClient;
    /** Prompt模板服务，用于加载和渲染 query intent 分类Prompt。 */
    private final PromptTemplateService promptTemplateService;

    /**
     * @Description: 初始化输入意图判定依赖。
     * @Logic: 保存词典、配置、模型和Prompt服务，供规则判定与LLM兜底分类复用。
     * @Param: dictionaryService 本地词典服务；reportQualityProperties 配置；qwenClient 模型客户端；promptTemplateService Prompt服务。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public ResearchQueryIntentService(QueryGuardrailDictionaryService dictionaryService,
                                      ReportQualityProperties reportQualityProperties,
                                      QwenClient qwenClient,
                                      PromptTemplateService promptTemplateService) {
        // 保存本地词典服务，规则分类需要读取拒绝词、动作词、主题词、行业词和代码正则。
        this.dictionaryService = dictionaryService;
        // 保存质量配置，输入护栏开关和阈值均从该配置读取。
        this.reportQualityProperties = reportQualityProperties;
        // 保存模型客户端，规则低置信时用于 LLM 兜底分类。
        this.qwenClient = qwenClient;
        // 保存 Prompt 服务，LLM 分类前需要加载并渲染分类 Prompt。
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * @Description: 判定用户输入是否可进入投研推荐链路。
     * @Logic: 空输入和禁用配置直接拒绝或放行；规则高置信直接返回，规则低置信调用LLM兜底，失败时保守拒绝。
     * @Param: query 用户输入原文。
     * @Return: 输入意图判定结果，包含意图、置信度、原因、规范化query和来源。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public QueryIntentDecision classify(String query) {
        // 护栏关闭时直接放行为完整分析，便于本地调试或灰度回滚。
        if (!reportQualityProperties.getQueryGuardrail().isEnabled()) {
            // 返回规则来源的高置信 ANALYZE，并保留裁剪后的 query。
            return new QueryIntentDecision(QueryIntentEnum.ANALYZE, DIRECT_CONFIDENCE, "Query guardrail disabled", safeTrim(query), RULE_SOURCE);
        }
        // 先执行本地规则分类，低成本处理明显的寒暄、代码、行业和主题输入。
        RuleDecision ruleDecision = classifyByRules(query);
        // 读取规则高置信阈值，达到该阈值时无需调用 LLM。
        double highThreshold = reportQualityProperties.getQueryGuardrail().getRuleHighConfidenceThreshold();
        // 规则结果足够可信时直接返回。
        if (ruleDecision.confidence() >= highThreshold) {
            // 将内部规则结果转换为对外统一判定对象。
            return ruleDecision.toDecision();
        }
        // 规则低置信且配置允许时，调用 LLM 做兜底分类。
        if (reportQualityProperties.getQueryGuardrail().isLlmFallbackEnabled()) {
            // 请求 LLM 输出结构化 intent/confidence/reason/normalizedQuery。
            QueryIntentDecision llmDecision = classifyByLlm(query);
            // LLM 置信度达到阈值且 intent 可解析时采纳模型结果。
            if (llmDecision.confidence() >= reportQualityProperties.getQueryGuardrail().getLlmConfidenceThreshold()
                    // intent 非空表示模型输出映射到了合法枚举。
                    && llmDecision.intent() != null) {
                // 返回 LLM 判定结果进入后续召回或拒绝分支。
                return llmDecision;
            }
        }
        // LLM 不可用或低置信时，如果规则超过低置信阈值，仍采用规则结果。
        if (ruleDecision.confidence() >= reportQualityProperties.getQueryGuardrail().getRuleLowConfidenceThreshold()) {
            // 返回低置信但可接受的规则结果，避免过度拒绝投研短 query。
            return ruleDecision.toDecision();
        }
        // 规则和 LLM 都不可信时保守拒绝，防止寒暄等输入触发召回和模型生成。
        return reject("输入缺少明确投研意图或模型分类低置信", query, FALLBACK_SOURCE);
    }

    /**
     * @Description: 使用本地词典规则进行输入分类。
     * @Logic: 先识别寒暄拒绝和股票代码，再结合投研动作、主题词、行业词判断完整分析或主题研究。
     * @Param: query 用户输入原文。
     * @Return: 规则分类结果。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private RuleDecision classifyByRules(String query) {
        // 标准化 query，便于中文短语和英文大小写匹配。
        String normalized = normalize(query);
        // 空输入直接拒绝。
        if (normalized.isBlank()) {
            // 返回高置信 REJECT，后续链路会在召回前短路。
            return new RuleDecision(QueryIntentEnum.REJECT, DIRECT_CONFIDENCE, "空输入", "", RULE_SOURCE);
        }
        // 寒暄、感谢、身份询问等短语不属于投研问题。
        if (matchesRejectPhrase(normalized)) {
            // 返回高置信 REJECT，并保留原始 query 的裁剪文本。
            return new RuleDecision(QueryIntentEnum.REJECT, DIRECT_CONFIDENCE, "命中非投研寒暄词", safeTrim(query), RULE_SOURCE);
        }
        // 股票代码是强标的锚点，命中后直接进入个股分析。
        if (matchesTicker(query)) {
            // 返回高置信 ANALYZE，后续会使用该 query 召回证券研报。
            return new RuleDecision(QueryIntentEnum.ANALYZE, DIRECT_CONFIDENCE, "命中股票代码", safeTrim(query), RULE_SOURCE);
        }
        // 判断 query 是否包含“分析、风险、估值”等投研动作词。
        boolean hasAction = containsAny(normalized, dictionaryService.researchActions());
        // 判断 query 是否包含主题词，例如储能、AI、机器人等。
        boolean hasTheme = containsAny(normalized, dictionaryService.themeTerms());
        // 判断 query 是否包含行业词，例如电力设备、港口、医药等。
        boolean hasIndustry = containsAny(normalized, dictionaryService.industryTerms());
        // 同时命中主题/行业和动作词时，判为主题研究且置信度较高。
        if ((hasTheme || hasIndustry) && hasAction) {
            // 返回主题研究，允许后续召回行业或主题相关公司候选。
            return new RuleDecision(QueryIntentEnum.THEME_RESEARCH, 0.92D, "命中主题/行业与投研动作", safeTrim(query), RULE_SOURCE);
        }
        // 只命中主题或行业词时，也允许进入主题研究链路。
        if (hasTheme || hasIndustry) {
            // 返回主题研究但置信度略低，保留 LLM 或低阈值兜底空间。
            return new RuleDecision(QueryIntentEnum.THEME_RESEARCH, 0.85D, "命中主题或行业词", safeTrim(query), RULE_SOURCE);
        }
        // 只命中投研动作词时，可能是泛化分析问题，按直接分析低置信放行。
        if (hasAction) {
            // 返回 ANALYZE，后续召回和证据护栏会继续判断证据是否足够。
            return new RuleDecision(QueryIntentEnum.ANALYZE, 0.82D, "命中投研动作词", safeTrim(query), RULE_SOURCE);
        }
        // 未命中任何投研信号时，规则倾向拒绝。
        return new RuleDecision(QueryIntentEnum.REJECT, 0.2D, "规则未命中投研信号", safeTrim(query), RULE_SOURCE);
    }

    /**
     * @Description: 调用 LLM 执行兜底输入分类。
     * @Logic: 渲染分类Prompt并请求结构化结果；异常、空结果或未知枚举均回退为低置信拒绝。
     * @Param: query 用户输入原文。
     * @Return: LLM分类结果或保守拒绝结果。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private QueryIntentDecision classifyByLlm(String query) {
        // LLM 分类失败不应中断主链路，因此统一捕获运行时异常。
        try {
            // 加载 query 意图分类 system Prompt。
            String systemPrompt = promptTemplateService.loadTemplate("prompts/query-intent-system-prompt.txt");
            // 渲染 user Prompt，仅注入裁剪后的用户 query。
            String userPrompt = promptTemplateService.render("prompts/query-intent-user-prompt.txt", Map.of("query", safeTrim(query)));
            // 请求模型返回结构化意图分类 DTO。
            LlmQueryIntentRespDTO response = qwenClient.chatForEntity(systemPrompt, userPrompt, LlmQueryIntentRespDTO.class);
            // 模型未配置或无响应时，按低置信拒绝处理。
            if (response == null) {
                // 返回 LLM 来源的拒绝结果，供上层判断置信度。
                return reject("LLM分类不可用", query, LLM_SOURCE);
            }
            // 将模型 intent 字符串解析为内部枚举。
            QueryIntentEnum intent = parseIntent(response.intent());
            // 模型置信度为空时按 0 处理，避免误放行。
            double confidence = response.confidence() == null ? 0D : response.confidence();
            // 模型未给 normalizedQuery 时使用原 query 裁剪文本。
            String normalizedQuery = response.normalizedQuery() == null || response.normalizedQuery().isBlank()
                    // 原 query 兜底，保证召回输入非 null。
                    ? safeTrim(query)
                    // 模型归一化 query 去除首尾空白后进入召回。
                    : response.normalizedQuery().trim();
            // intent 解析失败时不能采纳模型结果。
            if (intent == null) {
                // 返回未知分类拒绝，后续可能回退到规则低置信结果。
                return reject("LLM返回未知分类", query, LLM_SOURCE);
            }
            // 返回 LLM 分类结果，包含原因和规范化 query。
            return new QueryIntentDecision(intent, confidence, safeText(response.reason()), normalizedQuery, LLM_SOURCE);
        } catch (RuntimeException e) {
            // 模型调用或解析异常时保守拒绝，避免异常输入进入召回。
            return reject("LLM分类失败", query, LLM_SOURCE);
        }
    }

    /**
     * @Description: 构造保守拒绝的输入意图结果。
     * @Logic: 将意图固定为 REJECT、置信度固定为 0，并保留原始 query 与来源，供调用方短路召回。
     * @Param: reason 拒绝原因；query 用户输入原文；source 判定来源。
     * @Return: 不可分析输入判定结果。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private QueryIntentDecision reject(String reason, String query, String source) {
        // 构造统一 REJECT 判定对象，置信度固定为 0。
        return new QueryIntentDecision(QueryIntentEnum.REJECT, 0D, reason, safeTrim(query), source);
    }

    /**
     * @Description: 将模型返回的意图字符串转换为枚举。
     * @Logic: 空值和未知枚举返回 null，由调用方按低置信或解析失败路径保守拒绝。
     * @Param: value 模型返回的 intent 字段。
     * @Return: 匹配到的 QueryIntentEnum；无法识别时返回 null。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private QueryIntentEnum parseIntent(String value) {
        // 空 intent 无法映射到枚举。
        if (value == null || value.isBlank()) {
            // 返回 null 让调用方走拒绝或兜底分支。
            return null;
        }
        // 尝试按大写枚举名解析模型输出。
        try {
            // 去空白并转大写，兼容模型输出大小写差异。
            return QueryIntentEnum.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 未知枚举值返回 null，不抛异常影响主链路。
            return null;
        }
    }

    /**
     * @Description: 判断用户输入是否命中不可分析短语。
     * @Logic: 对短输入支持包含匹配，对完整短语支持等值匹配，避免长投研问题因包含礼貌词被误拒。
     * @Param: normalized 去空白小写后的用户输入。
     * @Return: 命中寒暄、感谢、身份询问等拒绝短语时返回 true。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private boolean matchesRejectPhrase(String normalized) {
        // 遍历拒绝短语词典并做规范化匹配。
        return dictionaryService.rejectPhrases().stream()
                // 词典项规范化，保证空白和大小写差异不影响匹配。
                .map(this::normalize)
                // 完全相等或短输入包含拒绝词时判定为不可分析。
                .anyMatch(phrase -> normalized.equals(phrase) || (normalized.length() <= 12 && normalized.contains(phrase)));
    }

    /**
     * @Description: 判断用户输入是否包含股票代码。
     * @Logic: 使用词典中的股票代码正则扫描原始 query，命中则认为可进入单标的分析。
     * @Param: query 用户输入原文。
     * @Return: 命中股票代码正则时返回 true。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private boolean matchesTicker(String query) {
        // null query 统一按空字符串处理，避免正则匹配空指针。
        String safeQuery = query == null ? "" : query;
        // 任一股票代码正则命中即可认为 query 有明确标的。
        return dictionaryService.tickerPatterns().stream().anyMatch(pattern -> pattern.matcher(safeQuery).find());
    }

    /**
     * @Description: 判断规范化文本是否包含任一词典项。
     * @Logic: 逐项规范化词典条目并做 contains 匹配，空词条会被过滤掉。
     * @Param: normalized 去空白小写后的输入文本；terms 候选词典项。
     * @Return: 命中任一词典项时返回 true。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private boolean containsAny(String normalized, List<String> terms) {
        // 将词典项逐个规范化后执行 contains 匹配。
        return terms.stream()
                // 规范化词典项。
                .map(this::normalize)
                // 过滤空词条，避免空字符串导致任何输入都命中。
                .filter(term -> !term.isBlank())
                // 任一词典项出现在 normalized query 中即命中。
                .anyMatch(normalized::contains);
    }

    /**
     * @Description: 标准化文本用于规则匹配。
     * @Logic: null 转为空字符串，移除所有空白并转小写，提升中文短语和英文代码匹配稳定性。
     * @Param: text 原始文本。
     * @Return: 规范化后的文本。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private String normalize(String text) {
        // null 转空字符串；非空文本去除所有空白并转小写。
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * @Description: 安全裁剪文本。
     * @Logic: null 转为空字符串，非空文本去除首尾空白，用于对外保留用户输入或模型归一化 query。
     * @Param: text 原始文本。
     * @Return: 非 null 的裁剪后文本。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private String safeTrim(String text) {
        // null 转空字符串；非空文本去除首尾空白。
        return text == null ? "" : text.trim();
    }

    /**
     * @Description: 安全处理模型返回文本。
     * @Logic: null 转为空字符串，非空文本去除首尾空白，用于存储 LLM 分类原因。
     * @Param: text 模型返回文本。
     * @Return: 非 null 的裁剪后文本。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private String safeText(String text) {
        // null 转空字符串；非空文本去除首尾空白。
        return text == null ? "" : text.trim();
    }

    /**
     * @Description: 输入意图判定结果。
     * @Logic: classify 方法统一返回该对象，供推荐服务决定是否召回、如何降级以及如何展示判定来源。
     * @Param: intent 输入意图；confidence 判定置信度；reason 判定原因；normalizedQuery 规范化后的召回query；source 判定来源。
     */
    public record QueryIntentDecision(
            /** 投研输入意图枚举，决定后续是否进入召回和生成链路。 */
            QueryIntentEnum intent,
            /** 判定置信度，用于区分规则强命中、LLM可接受结果和保守拒绝。 */
            double confidence,
            /** 判定原因，用于调试和前端/日志解释。 */
            String reason,
            /** 规范化后的query，作为召回服务的实际检索输入。 */
            String normalizedQuery,
            /** 判定来源，取值为 RULE、LLM 或 FALLBACK。 */
            String source
    ) {
    }

    /**
     * @Description: 规则分类内部结果。
     * @Logic: 仅在服务内部使用，便于在达到阈值后转换为对外统一的 QueryIntentDecision。
     * @Param: intent 输入意图；confidence 规则置信度；reason 规则原因；normalizedQuery 规范化后的query；source 判定来源。
     */
    private record RuleDecision(
            /** 规则判定得到的输入意图。 */
            QueryIntentEnum intent,
            /** 规则判定置信度。 */
            double confidence,
            /** 规则命中或拒绝原因。 */
            String reason,
            /** 规则判定后保留的规范化query。 */
            String normalizedQuery,
            /** 规则结果来源标识，通常为 RULE。 */
            String source
    ) {
        /**
         * @Description: 将规则内部结果转换为统一对外判定结果。
         * @Logic: 直接复制规则判定字段，保持 classify 方法只暴露 QueryIntentDecision。
         * @Param: 无。
         * @Return: 统一输入意图判定结果。
         * @author: cx
         * @Date: 2026-05-24 00:45:00
         */
        private QueryIntentDecision toDecision() {
            // 直接复制规则判定字段，输出统一 QueryIntentDecision。
            return new QueryIntentDecision(intent, confidence, reason, normalizedQuery, source);
        }
    }
}
