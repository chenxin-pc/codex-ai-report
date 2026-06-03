package com.example.aimilvusweb.evaluation.service;

import com.alibaba.fastjson2.JSON;
import com.example.aimilvusweb.evaluation.dto.EvaluationAutoScoreRespDTO;
import com.example.aimilvusweb.evaluation.dto.EvaluationCaseBundleDTO;
import com.example.aimilvusweb.evaluation.entity.EvaluationCase;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseAnchor;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseForbiddenContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseRun;
import com.example.aimilvusweb.evaluation.entity.EvaluationReferenceContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationRetrievedContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @Description: Eval case 自动判分服务，基于标准 case 和运行结果计算项目内检索、输出和禁用项指标。
 * @Logic: 对比期望意图、锚点、输出等级、降级原因、标准证据命中、禁止上下文和禁止结论，生成可写回 case run 的判分 DTO。
 * @Param: 无。
 * @Return: 自动判分 DTO。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Service
public class EvaluationAutoScoringService {

    /** FINAL 阶段名称，表示推荐链路实际交给模型或前端的证据。 */
    private static final String STAGE_FINAL = "FINAL";

    /**
     * @Description: 计算单条 case run 的自动判分。
     * @Logic: 对每个可计算指标分别判定，缺少标准时返回 null；任一硬失败会将 needsManualReview 标记为 true。
     * @Param: bundle eval case 标准聚合；caseRun 本次运行结果；retrievedContexts 检索上下文列表。
     * @Return: 自动判分 DTO。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    public EvaluationAutoScoreRespDTO score(EvaluationCaseBundleDTO bundle,
                                            EvaluationCaseRun caseRun,
                                            List<EvaluationRetrievedContext> retrievedContexts) {
        // 读取 case 主体标准，后续所有判分都以该对象为基准。
        EvaluationCase evaluationCase = bundle.evaluationCase();
        // 逐项计算可独立解释的项目内指标。
        Boolean intentMatch = matchExpectedText(evaluationCase.getExpectedIntent(), caseRun.getActualIntent());
        Boolean anchorMatch = matchAnchors(bundle.anchors(), caseRun.getActualAnchors());
        Boolean outputLevelMatch = matchExpectedText(evaluationCase.getExpectedOutputLevel(), caseRun.getActualOutputLevel());
        Boolean degradationReasonMatch = matchExpectedTokens(evaluationCase.getExpectedDegradationReasons(), caseRun.getActualDegradationReasons());
        Boolean referenceContextHit = matchReferenceContexts(bundle.referenceContexts(), retrievedContexts);
        Boolean forbiddenContextHit = hitForbiddenContexts(bundle.forbiddenContexts(), retrievedContexts);
        Boolean forbiddenClaimHit = hitForbiddenClaims(evaluationCase, caseRun.getResponseText());
        Boolean evidenceQualityMatch = resolveEvidenceQualityMatch(referenceContextHit, forbiddenContextHit, forbiddenClaimHit);
        // 需要复核的条件偏保守：任一明确 false 或命中禁止项都进入人工复核。
        Boolean needsManualReview = needsManualReview(intentMatch, outputLevelMatch, degradationReasonMatch,
                referenceContextHit, forbiddenContextHit, forbiddenClaimHit);
        // 详情 JSON 保留指标原值，便于脚本导出 metadata。
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("intentMatch", intentMatch);
        detail.put("anchorMatch", anchorMatch);
        detail.put("outputLevelMatch", outputLevelMatch);
        detail.put("degradationReasonMatch", degradationReasonMatch);
        detail.put("referenceContextHit", referenceContextHit);
        detail.put("forbiddenContextHit", forbiddenContextHit);
        detail.put("forbiddenClaimHit", forbiddenClaimHit);
        detail.put("evidenceQualityMatch", evidenceQualityMatch);
        detail.put("needsManualReview", needsManualReview);
        String detailJson = JSON.toJSONString(detail);
        return new EvaluationAutoScoreRespDTO(intentMatch, anchorMatch, outputLevelMatch, degradationReasonMatch,
                referenceContextHit, forbiddenContextHit, forbiddenClaimHit, evidenceQualityMatch,
                needsManualReview, detailJson);
    }

    /**
     * @Description: 判断期望文本是否与实际文本匹配。
     * @Logic: 期望为空表示不可计算；非空时执行大小写不敏感的精确比较。
     * @Param: expected 期望文本；actual 实际文本。
     * @Return: true 表示匹配，false 表示不匹配，null 表示不可计算。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private Boolean matchExpectedText(String expected, String actual) {
        if (isBlank(expected)) {
            return null;
        }
        return normalize(expected).equals(normalize(actual));
    }

    /**
     * @Description: 判断实际锚点是否覆盖 required 标准锚点。
     * @Logic: 没有 required 锚点时返回 null；否则要求 actualAnchors 文本包含锚点编码或名称。
     * @Param: anchors 标准锚点列表；actualAnchors 实际锚点文本。
     * @Return: true 表示覆盖，false 表示缺失，null 表示不可计算。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private Boolean matchAnchors(List<EvaluationCaseAnchor> anchors, String actualAnchors) {
        List<EvaluationCaseAnchor> requiredAnchors = safeList(anchors).stream()
                .filter(anchor -> anchor.getRequired() == null || anchor.getRequired())
                .toList();
        if (requiredAnchors.isEmpty()) {
            return null;
        }
        String normalizedActual = normalize(actualAnchors);
        for (EvaluationCaseAnchor anchor : requiredAnchors) {
            if (!containsAny(normalizedActual, anchor.getAnchorCode(), anchor.getAnchorName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * @Description: 判断实际 token 集合是否覆盖期望 token 集合。
     * @Logic: 期望为空表示不可计算；期望可为 JSON 数组、逗号分隔或换行文本。
     * @Param: expectedTokens 期望 token 文本；actualTokens 实际 token 文本。
     * @Return: true 表示全部覆盖，false 表示缺失，null 表示不可计算。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private Boolean matchExpectedTokens(String expectedTokens, String actualTokens) {
        List<String> expected = parseTokens(expectedTokens);
        if (expected.isEmpty()) {
            return null;
        }
        String normalizedActual = normalize(actualTokens);
        for (String token : expected) {
            if (!normalizedActual.contains(normalize(token))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @Description: 判断 FINAL 检索结果是否命中标准证据。
     * @Logic: 没有标准证据时返回 null；有标准证据时按 chunkUid 或 parentChunkUid 命中任一即可。
     * @Param: referenceContexts 标准证据列表；retrievedContexts 实际检索上下文。
     * @Return: true 表示命中，false 表示未命中，null 表示不可计算。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private Boolean matchReferenceContexts(List<EvaluationReferenceContext> referenceContexts,
                                           List<EvaluationRetrievedContext> retrievedContexts) {
        if (safeList(referenceContexts).isEmpty()) {
            return null;
        }
        List<EvaluationRetrievedContext> finalContexts = finalContexts(retrievedContexts);
        for (EvaluationReferenceContext referenceContext : referenceContexts) {
            if (hitReference(referenceContext, finalContexts)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @Description: 判断检索上下文是否命中禁止项。
     * @Logic: 没有禁止项时返回 false；禁止项会扫描 chunkUid、parentChunkUid、metadataJson、sectionPath 和 retrievedText。
     * @Param: forbiddenContexts 禁止命中项列表；retrievedContexts 实际检索上下文。
     * @Return: true 表示命中污染证据，false 表示未命中。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private Boolean hitForbiddenContexts(List<EvaluationCaseForbiddenContext> forbiddenContexts,
                                         List<EvaluationRetrievedContext> retrievedContexts) {
        for (EvaluationCaseForbiddenContext forbiddenContext : safeList(forbiddenContexts)) {
            String forbidden = normalize(forbiddenContext.getForbiddenValue());
            for (EvaluationRetrievedContext retrievedContext : safeList(retrievedContexts)) {
                if (!forbidden.isBlank() && contextSearchText(retrievedContext).contains(forbidden)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @Description: 判断响应文本是否命中禁止结论。
     * @Logic: forbiddenClaims 和 forbiddenTerms 均会参与扫描，任一命中表示高确定性或污染结论风险。
     * @Param: evaluationCase case 标准；responseText 响应文本。
     * @Return: true 表示命中禁用结论，false 表示未命中。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private Boolean hitForbiddenClaims(EvaluationCase evaluationCase, String responseText) {
        String normalizedResponse = normalize(responseText);
        List<String> forbiddenTokens = new ArrayList<>();
        forbiddenTokens.addAll(parseTokens(evaluationCase.getForbiddenClaims()));
        forbiddenTokens.addAll(parseTokens(evaluationCase.getForbiddenTerms()));
        for (String token : forbiddenTokens) {
            if (!isBlank(token) && normalizedResponse.contains(normalize(token))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @Description: 汇总证据质量匹配结果。
     * @Logic: 禁止证据或禁止结论命中时直接失败；标准证据未命中时失败；缺少标准证据时返回 null。
     * @Param: referenceContextHit 标准证据命中；forbiddenContextHit 禁止证据命中；forbiddenClaimHit 禁止结论命中。
     * @Return: true 表示证据质量可接受，false 表示失败，null 表示不可计算。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private Boolean resolveEvidenceQualityMatch(Boolean referenceContextHit,
                                                Boolean forbiddenContextHit,
                                                Boolean forbiddenClaimHit) {
        if (Boolean.TRUE.equals(forbiddenContextHit) || Boolean.TRUE.equals(forbiddenClaimHit)) {
            return false;
        }
        if (referenceContextHit == null) {
            return null;
        }
        return referenceContextHit;
    }

    /**
     * @Description: 判断是否需要人工复核。
     * @Logic: 任一明确不匹配或命中禁止项时返回 true；不可计算指标本身不强制复核。
     * @Param: intentMatch 意图匹配；outputLevelMatch 输出等级匹配；degradationReasonMatch 降级原因匹配；referenceContextHit 标准证据命中；forbiddenContextHit 禁止证据命中；forbiddenClaimHit 禁止结论命中。
     * @Return: true 表示建议人工复核。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private Boolean needsManualReview(Boolean intentMatch,
                                      Boolean outputLevelMatch,
                                      Boolean degradationReasonMatch,
                                      Boolean referenceContextHit,
                                      Boolean forbiddenContextHit,
                                      Boolean forbiddenClaimHit) {
        return Boolean.FALSE.equals(intentMatch)
                || Boolean.FALSE.equals(outputLevelMatch)
                || Boolean.FALSE.equals(degradationReasonMatch)
                || Boolean.FALSE.equals(referenceContextHit)
                || Boolean.TRUE.equals(forbiddenContextHit)
                || Boolean.TRUE.equals(forbiddenClaimHit);
    }

    /**
     * @Description: 判断标准证据是否命中实际检索结果。
     * @Logic: 标准证据的 chunkUid 或 parentChunkUid 与任一 FINAL retrieved context 相等即命中。
     * @Param: referenceContext 标准证据；retrievedContexts FINAL 检索上下文。
     * @Return: true 表示命中。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private boolean hitReference(EvaluationReferenceContext referenceContext, List<EvaluationRetrievedContext> retrievedContexts) {
        for (EvaluationRetrievedContext retrievedContext : retrievedContexts) {
            if (sameText(referenceContext.getChunkUid(), retrievedContext.getChunkUid())
                    || sameText(referenceContext.getParentChunkUid(), retrievedContext.getParentChunkUid())
                    || sameText(referenceContext.getChunkUid(), retrievedContext.getParentChunkUid())
                    || sameText(referenceContext.getParentChunkUid(), retrievedContext.getChunkUid())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @Description: 筛选 FINAL 阶段检索上下文。
     * @Logic: 如果没有显式阶段匹配，则返回空列表，避免用诊断候选误判标准证据命中。
     * @Param: retrievedContexts 原始检索上下文。
     * @Return: FINAL 阶段上下文列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private List<EvaluationRetrievedContext> finalContexts(List<EvaluationRetrievedContext> retrievedContexts) {
        return safeList(retrievedContexts).stream()
                .filter(context -> STAGE_FINAL.equalsIgnoreCase(defaultString(context.getStage())))
                .toList();
    }

    /**
     * @Description: 拼接检索上下文中可供污染规则扫描的文本。
     * @Logic: 聚合 chunkUid、parentChunkUid、sectionPath、retrievedText 和 metadataJson，统一小写后返回。
     * @Param: retrievedContext 检索上下文。
     * @Return: 可搜索文本。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String contextSearchText(EvaluationRetrievedContext retrievedContext) {
        return normalize(String.join(" ",
                defaultString(retrievedContext.getChunkUid()),
                defaultString(retrievedContext.getParentChunkUid()),
                defaultString(retrievedContext.getSectionPath()),
                defaultString(retrievedContext.getRetrievedText()),
                defaultString(retrievedContext.getMetadataJson())));
    }

    /**
     * @Description: 判断目标文本是否包含候选编码或名称。
     * @Logic: 候选值为空时忽略；编码或名称任一命中即返回 true。
     * @Param: normalizedActual 已规范化实际文本；candidates 候选值数组。
     * @Return: true 表示至少一个候选命中。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private boolean containsAny(String normalizedActual, String... candidates) {
        for (String candidate : candidates) {
            if (!isBlank(candidate) && normalizedActual.contains(normalize(candidate))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @Description: 解析 JSON 数组或分隔文本为 token 列表。
     * @Logic: 先尝试按 JSON 数组解析，失败后按逗号、分号、顿号和换行拆分。
     * @Param: text 原始 token 文本。
     * @Return: 去空白后的 token 列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private List<String> parseTokens(String text) {
        if (isBlank(text)) {
            return List.of();
        }
        try {
            List<String> values = JSON.parseArray(text, String.class);
            return values == null ? List.of() : values.stream().filter(value -> !isBlank(value)).map(String::trim).toList();
        } catch (RuntimeException ignored) {
            String[] parts = text.split("[,;；，、\\n\\r]+");
            List<String> values = new ArrayList<>(parts.length);
            for (String part : parts) {
                if (!isBlank(part)) {
                    values.add(part.trim());
                }
            }
            return values;
        }
    }

    /**
     * @Description: 规范化文本用于比较。
     * @Logic: null 转空字符串，文本去首尾空白并转小写。
     * @Param: value 输入文本。
     * @Return: 规范化文本。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String normalize(String value) {
        return defaultString(value).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @Description: 判断两个文本规范化后是否相等。
     * @Logic: 空文本不会与空文本构成有效命中，避免缺失 ID 误判。
     * @Param: left 左文本；right 右文本。
     * @Return: true 表示非空且相等。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private boolean sameText(String left, String right) {
        return !isBlank(left) && normalize(left).equals(normalize(right));
    }

    /**
     * @Description: 判断文本是否为空白。
     * @Logic: null 或去空白后为空都视为空白。
     * @Param: value 输入文本。
     * @Return: true 表示空白。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * @Description: 将 null 文本转为空字符串。
     * @Logic: 统一比较和拼接流程，避免空指针。
     * @Param: value 输入文本。
     * @Return: 原文本或空字符串。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    /**
     * @Description: 将可能为 null 的列表转为空列表。
     * @Logic: 避免判分流程中多处空值分支。
     * @Param: values 原始列表。
     * @Return: 原列表或空列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
