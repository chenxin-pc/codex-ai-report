package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.llm.QwenClient;
import com.example.aimilvusweb.common.prompt.PromptTemplateService;
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
        this.dictionaryService = dictionaryService;
        this.reportQualityProperties = reportQualityProperties;
        this.qwenClient = qwenClient;
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
        if (!reportQualityProperties.getQueryGuardrail().isEnabled()) {
            return new QueryIntentDecision(QueryIntentEnum.ANALYZE, DIRECT_CONFIDENCE, "Query guardrail disabled", safeTrim(query), RULE_SOURCE);
        }
        RuleDecision ruleDecision = classifyByRules(query);
        double highThreshold = reportQualityProperties.getQueryGuardrail().getRuleHighConfidenceThreshold();
        if (ruleDecision.confidence() >= highThreshold) {
            return ruleDecision.toDecision();
        }
        if (reportQualityProperties.getQueryGuardrail().isLlmFallbackEnabled()) {
            QueryIntentDecision llmDecision = classifyByLlm(query);
            if (llmDecision.confidence() >= reportQualityProperties.getQueryGuardrail().getLlmConfidenceThreshold()
                    && llmDecision.intent() != null) {
                return llmDecision;
            }
        }
        if (ruleDecision.confidence() >= reportQualityProperties.getQueryGuardrail().getRuleLowConfidenceThreshold()) {
            return ruleDecision.toDecision();
        }
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
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return new RuleDecision(QueryIntentEnum.REJECT, DIRECT_CONFIDENCE, "空输入", "", RULE_SOURCE);
        }
        if (matchesRejectPhrase(normalized)) {
            return new RuleDecision(QueryIntentEnum.REJECT, DIRECT_CONFIDENCE, "命中非投研寒暄词", safeTrim(query), RULE_SOURCE);
        }
        if (matchesTicker(query)) {
            return new RuleDecision(QueryIntentEnum.ANALYZE, DIRECT_CONFIDENCE, "命中股票代码", safeTrim(query), RULE_SOURCE);
        }
        boolean hasAction = containsAny(normalized, dictionaryService.researchActions());
        boolean hasTheme = containsAny(normalized, dictionaryService.themeTerms());
        boolean hasIndustry = containsAny(normalized, dictionaryService.industryTerms());
        if ((hasTheme || hasIndustry) && hasAction) {
            return new RuleDecision(QueryIntentEnum.THEME_RESEARCH, 0.92D, "命中主题/行业与投研动作", safeTrim(query), RULE_SOURCE);
        }
        if (hasTheme || hasIndustry) {
            return new RuleDecision(QueryIntentEnum.THEME_RESEARCH, 0.85D, "命中主题或行业词", safeTrim(query), RULE_SOURCE);
        }
        if (hasAction) {
            return new RuleDecision(QueryIntentEnum.ANALYZE, 0.82D, "命中投研动作词", safeTrim(query), RULE_SOURCE);
        }
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
        try {
            String systemPrompt = promptTemplateService.loadTemplate("prompts/query-intent-system-prompt.txt");
            String userPrompt = promptTemplateService.render("prompts/query-intent-user-prompt.txt", Map.of("query", safeTrim(query)));
            LlmQueryIntentRespDTO response = qwenClient.chatForEntity(systemPrompt, userPrompt, LlmQueryIntentRespDTO.class);
            if (response == null) {
                return reject("LLM分类不可用", query, LLM_SOURCE);
            }
            QueryIntentEnum intent = parseIntent(response.intent());
            double confidence = response.confidence() == null ? 0D : response.confidence();
            String normalizedQuery = response.normalizedQuery() == null || response.normalizedQuery().isBlank()
                    ? safeTrim(query)
                    : response.normalizedQuery().trim();
            if (intent == null) {
                return reject("LLM返回未知分类", query, LLM_SOURCE);
            }
            return new QueryIntentDecision(intent, confidence, safeText(response.reason()), normalizedQuery, LLM_SOURCE);
        } catch (RuntimeException e) {
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
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return QueryIntentEnum.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
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
        return dictionaryService.rejectPhrases().stream()
                .map(this::normalize)
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
        String safeQuery = query == null ? "" : query;
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
        return terms.stream()
                .map(this::normalize)
                .filter(term -> !term.isBlank())
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
            return new QueryIntentDecision(intent, confidence, reason, normalizedQuery, source);
        }
    }
}
