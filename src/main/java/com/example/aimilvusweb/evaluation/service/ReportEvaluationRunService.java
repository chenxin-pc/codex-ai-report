package com.example.aimilvusweb.evaluation.service;

import com.alibaba.fastjson2.JSON;
import com.example.aimilvusweb.dto.RecommendRespDTO;
import com.example.aimilvusweb.dto.TopResultRespDTO;
import com.example.aimilvusweb.evaluation.dto.EvaluationAutoScoreRespDTO;
import com.example.aimilvusweb.evaluation.dto.EvaluationCaseBundleDTO;
import com.example.aimilvusweb.evaluation.dto.EvaluationRunSnapshotReqDTO;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseRun;
import com.example.aimilvusweb.evaluation.entity.EvaluationRetrievedContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationRun;
import com.example.aimilvusweb.evaluation.repository.EvaluationRunMapper;
import com.example.aimilvusweb.service.recommendation.ReportRecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @Description: 研报 RAG 评测运行服务，负责创建 eval run、执行 eval case、保存检索上下文和自动判分。
 * @Logic: 运行时复用真实推荐链路，推荐成功后写入 FINAL retrieved context 和 case run 结果，失败时记录错误并按配置继续或停止。
 * @Param: 无。
 * @Return: 评测运行和 case run 持久化对象。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Service
@RequiredArgsConstructor
public class ReportEvaluationRunService {

    /** 运行中状态。 */
    private static final String STATUS_RUNNING = "RUNNING";
    /** 运行成功状态。 */
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    /** 运行失败状态。 */
    private static final String STATUS_FAILED = "FAILED";
    /** 部分 case 失败状态。 */
    private static final String STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    /** FINAL 检索阶段。 */
    private static final String STAGE_FINAL = "FINAL";
    /** 缺省版本占位，表示当前环境无法读取真实值。 */
    private static final String UNKNOWN = "unknown";

    /** 评测运行 Mapper，负责 eval_run、eval_case_run 和 eval_retrieved_context 写入。 */
    private final EvaluationRunMapper evaluationRunMapper;
    /** 评测数据集服务，用于读取启用 case 及其标准证据。 */
    private final ReportEvaluationDatasetService datasetService;
    /** 自动判分服务，用于将标准 case 与推荐结果对比。 */
    private final EvaluationAutoScoringService autoScoringService;
    /** 真实推荐编排服务，评测运行通过它调用线上等价推荐链路。 */
    private final ReportRecommendService reportRecommendService;
    /** Spring 环境对象，用于读取模型和检索配置快照。 */
    private final Environment environment;

    /**
     * @Description: 创建 eval run 快照。
     * @Logic: runId 缺失时自动生成；模型、prompt、词典和检索配置缺失时使用 unknown 或当前配置摘要。
     * @Param: request eval run 快照请求。
     * @Return: 已写入并回填主键的 eval run。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    @Transactional
    public EvaluationRun createRun(EvaluationRunSnapshotReqDTO request) {
        // 校验 corpusId，确保运行一定归属于一个评测语料。
        if (request == null || request.corpusId() == null || request.corpusId() <= 0L) {
            throw new IllegalArgumentException("corpusId must be positive");
        }
        Instant now = Instant.now();
        EvaluationRun run = new EvaluationRun();
        // 生成稳定运行编号，并写入可复现快照。
        run.setRunId(defaultText(request.runId(), generatedRunId()));
        run.setCorpusId(request.corpusId());
        run.setAppCommit(defaultText(request.appCommit(), UNKNOWN));
        run.setPromptVersion(defaultText(request.promptVersion(), UNKNOWN));
        run.setPromptHash(defaultText(request.promptHash(), UNKNOWN));
        run.setEmbeddingModel(defaultText(request.embeddingModel(), property("spring.ai.openai.embedding.options.model")));
        run.setLlmModel(defaultText(request.llmModel(), property("spring.ai.openai.chat.options.model")));
        run.setRetrievalConfig(defaultText(request.retrievalConfig(), retrievalConfigSnapshot()));
        run.setDictionaryVersion(defaultText(request.dictionaryVersion(), property("app.report-quality.query-guardrail.dictionary-path")));
        run.setStatus(STATUS_RUNNING);
        run.setStartedAt(now);
        run.setCreatedAt(now);
        evaluationRunMapper.insertRun(run);
        return run;
    }

    /**
     * @Description: 执行指定 corpus 下启用的 eval case。
     * @Logic: 创建 run 后逐条调用推荐链路，保存 case run、FINAL retrieved context、自动判分和失败信息；最终更新 run 状态。
     * @Param: request eval run 快照请求；continueOnError 单条 case 失败后是否继续。
     * @Return: 已完成状态更新的 eval run。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    public EvaluationRun executeRun(EvaluationRunSnapshotReqDTO request, boolean continueOnError) {
        // 创建运行快照，后续所有 case run 都关联到该记录。
        EvaluationRun run = createRun(request);
        List<EvaluationCaseBundleDTO> bundles = datasetService.listEnabledCaseBundles(run.getCorpusId());
        boolean hasFailure = false;
        // 逐条执行 case；外部推荐失败时记录失败状态，并根据配置决定是否继续。
        for (EvaluationCaseBundleDTO bundle : bundles) {
            EvaluationCaseRun caseRun = startCaseRun(run, bundle);
            try {
                executeCaseRun(bundle, caseRun);
            } catch (RuntimeException ex) {
                hasFailure = true;
                evaluationRunMapper.updateCaseRunFailure(caseRun.getId(), shortError(ex), Instant.now());
                if (!continueOnError) {
                    break;
                }
            }
        }
        // 汇总运行状态，区分全部成功、部分失败和整体失败。
        String finalStatus = hasFailure ? STATUS_PARTIAL_FAILED : STATUS_SUCCEEDED;
        evaluationRunMapper.updateRunStatus(run.getId(), finalStatus, Instant.now(), hasFailure ? "Some eval cases failed" : null);
        run.setStatus(finalStatus);
        run.setFinishedAt(Instant.now());
        run.setErrorSummary(hasFailure ? "Some eval cases failed" : null);
        return run;
    }

    /**
     * @Description: 查询某次 eval run 的 case run 列表。
     * @Logic: 先按 runId 查 eval run，再按 evalRunId 查询子记录；未命中运行时返回空集合。
     * @Param: runId 外部运行编号。
     * @Return: case run 列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    public List<EvaluationCaseRun> listCaseRuns(String runId) {
        EvaluationRun run = evaluationRunMapper.selectRunByRunId(runId);
        if (run == null) {
            return List.of();
        }
        return evaluationRunMapper.selectCaseRunsByEvalRunId(run.getId());
    }

    /**
     * @Description: 查询某个 case run 的 retrieved context。
     * @Logic: 直接委托 Mapper 按阶段和排名返回上下文，供导出和测试使用。
     * @Param: caseRunId case run 主键。
     * @Return: retrieved context 列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    public List<EvaluationRetrievedContext> listRetrievedContexts(Long caseRunId) {
        if (caseRunId == null || caseRunId <= 0L) {
            return List.of();
        }
        return evaluationRunMapper.selectRetrievedContextsByCaseRunId(caseRunId);
    }

    /**
     * @Description: 创建 RUNNING 状态的 case run。
     * @Logic: 使用 eval case query 快照初始化执行记录，后续成功或失败更新同一条记录。
     * @Param: run eval run 父记录；bundle eval case 标准聚合。
     * @Return: 已写入并回填主键的 case run。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private EvaluationCaseRun startCaseRun(EvaluationRun run, EvaluationCaseBundleDTO bundle) {
        Instant now = Instant.now();
        EvaluationCaseRun caseRun = new EvaluationCaseRun();
        // 初始化 case run 的父子关联和 query 快照。
        caseRun.setEvalRunId(run.getId());
        caseRun.setCasePkId(bundle.evaluationCase().getId());
        caseRun.setQueryText(bundle.evaluationCase().getQueryText());
        caseRun.setStatus(STATUS_RUNNING);
        caseRun.setStartedAt(now);
        caseRun.setCreatedAt(now);
        evaluationRunMapper.insertCaseRun(caseRun);
        return caseRun;
    }

    /**
     * @Description: 执行单条 eval case 并保存成功结果。
     * @Logic: 调用真实推荐服务，保存 FINAL retrieved context，计算自动判分并更新 case run。
     * @Param: bundle eval case 标准聚合；caseRun RUNNING 状态 case run。
     * @Return: 无（副作用为写入检索上下文和更新 case run）。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private void executeCaseRun(EvaluationCaseBundleDTO bundle, EvaluationCaseRun caseRun) {
        // 使用真实推荐链路获取输入判定、检索结果、证据质量和生成输出。
        RecommendRespDTO response = reportRecommendService.recommend(bundle.evaluationCase().getQueryText());
        // 将 Top 结果保存为 FINAL retrieved context。
        List<EvaluationRetrievedContext> contexts = persistFinalContexts(caseRun.getId(), response.top5());
        // 将推荐响应映射到 case run 字段。
        fillCaseRunResponse(caseRun, response);
        // 计算项目内自动判分并写回 case run。
        EvaluationAutoScoreRespDTO score = autoScoringService.score(bundle, caseRun, contexts);
        fillCaseRunScore(caseRun, score);
        caseRun.setStatus(STATUS_SUCCEEDED);
        caseRun.setFinishedAt(Instant.now());
        evaluationRunMapper.updateCaseRunResult(caseRun);
    }

    /**
     * @Description: 持久化 FINAL Top 证据。
     * @Logic: 按响应顺序写入 rank、chunk 定位、文本、分数和 metadata 快照；Top 列表为空时返回空集合。
     * @Param: caseRunId case run 主键；topResults 推荐响应 Top 结果。
     * @Return: 已写入的 retrieved context 列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private List<EvaluationRetrievedContext> persistFinalContexts(Long caseRunId, List<TopResultRespDTO> topResults) {
        List<TopResultRespDTO> safeTopResults = safeList(topResults);
        List<EvaluationRetrievedContext> contexts = new ArrayList<>(safeTopResults.size());
        // 逐条 Top 证据写入 FINAL 阶段。
        for (int index = 0; index < safeTopResults.size(); index++) {
            TopResultRespDTO topResult = safeTopResults.get(index);
            EvaluationRetrievedContext context = new EvaluationRetrievedContext();
            context.setCaseRunId(caseRunId);
            context.setStage(STAGE_FINAL);
            context.setRankNo(index + 1);
            context.setChunkUid(topResult.chunkUid());
            context.setParentChunkUid(topResult.parentChunkUid());
            context.setSectionPath(topResult.sectionPath());
            context.setScore(toBigDecimal(topResult.score()));
            context.setRelevanceScore(toBigDecimal(topResult.score()));
            context.setContextType("PARENT_CONTEXT");
            context.setDiagnosticOnly(topResult.diagnosticOnly());
            context.setTruncated(Boolean.FALSE);
            context.setHitCount(1);
            context.setRetrievedText(defaultText(topResult.parentContext(), topResult.chunkText()));
            context.setMetadataJson(JSON.toJSONString(topResultMetadata(topResult)));
            context.setCreatedAt(Instant.now());
            evaluationRunMapper.insertRetrievedContext(context);
            contexts.add(context);
        }
        return contexts;
    }

    /**
     * @Description: 将推荐响应字段填入 case run。
     * @Logic: JSON 字段使用 fastjson2 序列化；responseText 拼接 analysis、recommendation 和 risks 以便 Ragas 导出。
     * @Param: caseRun 待填充 case run；response 推荐响应。
     * @Return: 无（仅修改 caseRun 对象）。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private void fillCaseRunResponse(EvaluationCaseRun caseRun, RecommendRespDTO response) {
        caseRun.setActualIntent(response.inputIntent());
        caseRun.setActualAnchors(response.evidenceQuality() == null ? "" : JSON.toJSONString(response.evidenceQuality().structuredAnchors()));
        caseRun.setActualOutputLevel(response.outputLevel());
        caseRun.setActualDegradationReasons(JSON.toJSONString(safeList(response.degradationReasons())));
        caseRun.setAnalysis(response.analysis());
        caseRun.setRecommendation(response.recommendation());
        caseRun.setRisks(JSON.toJSONString(safeList(response.risks())));
        caseRun.setCitations(JSON.toJSONString(safeList(response.citations())));
        caseRun.setEvidenceQuality(JSON.toJSONString(response.evidenceQuality()));
        caseRun.setResponseText(buildResponseText(response));
        caseRun.setManualReviewStatus("UNREVIEWED");
    }

    /**
     * @Description: 将自动判分字段填入 case run。
     * @Logic: 指标布尔值逐项复制，autoScores 保存完整详情 JSON，供导出和排障使用。
     * @Param: caseRun 待更新 case run；score 自动判分结果。
     * @Return: 无（仅修改 caseRun 对象）。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private void fillCaseRunScore(EvaluationCaseRun caseRun, EvaluationAutoScoreRespDTO score) {
        caseRun.setIntentMatch(score.intentMatch());
        caseRun.setAnchorMatch(score.anchorMatch());
        caseRun.setOutputLevelMatch(score.outputLevelMatch());
        caseRun.setDegradationReasonMatch(score.degradationReasonMatch());
        caseRun.setReferenceContextHit(score.referenceContextHit());
        caseRun.setForbiddenContextHit(score.forbiddenContextHit());
        caseRun.setForbiddenClaimHit(score.forbiddenClaimHit());
        caseRun.setEvidenceQualityMatch(score.evidenceQualityMatch());
        caseRun.setNeedsManualReview(Boolean.TRUE.equals(score.needsManualReview()));
        caseRun.setAutoScores(score.detailJson());
    }

    /**
     * @Description: 构建 Ragas 和人工复核使用的响应文本。
     * @Logic: 按 analysis、recommendation、risks 顺序拼接，空字段跳过，保留字段标题便于阅读。
     * @Param: response 推荐响应。
     * @Return: 完整响应文本。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String buildResponseText(RecommendRespDTO response) {
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "analysis", response.analysis());
        appendSection(builder, "recommendation", response.recommendation());
        appendSection(builder, "risks", JSON.toJSONString(safeList(response.risks())));
        return builder.toString().trim();
    }

    /**
     * @Description: 为响应文本追加非空段落。
     * @Logic: 空白内容跳过，非空内容用字段名加冒号标识来源。
     * @Param: builder 响应文本构造器；title 段落标题；content 段落内容。
     * @Return: 无（副作用为追加 builder 内容）。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(title).append(": ").append(content);
    }

    /**
     * @Description: 构建 TopResult metadata 快照。
     * @Logic: 使用 LinkedHashMap 保持字段顺序，并允许部分字段为空值。
     * @Param: topResult 推荐 Top 证据。
     * @Return: metadata 快照 Map。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private Map<String, Object> topResultMetadata(TopResultRespDTO topResult) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", topResult.title());
        metadata.put("source", topResult.source());
        metadata.put("themeCodes", topResult.themeCodes());
        metadata.put("industryCodes", topResult.industryCodes());
        metadata.put("companyNames", topResult.companyNames());
        metadata.put("tickers", topResult.tickers());
        metadata.put("diagnosticOnly", topResult.diagnosticOnly());
        return metadata;
    }

    /**
     * @Description: 读取配置属性并提供 unknown 兜底。
     * @Logic: 环境中无该配置或配置为空时返回 unknown，避免伪造版本值。
     * @Param: key 配置键。
     * @Return: 配置值或 unknown。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String property(String key) {
        return defaultText(environment.getProperty(key), UNKNOWN);
    }

    /**
     * @Description: 构建检索配置快照。
     * @Logic: 读取当前关键检索配置项，保存为 JSON 字符串供历史评测解释。
     * @Param: 无。
     * @Return: 检索配置 JSON。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String retrievalConfigSnapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("initialTopK", environment.getProperty("app.report-quality.retrieval.initial-top-k"));
        values.put("finalTopK", environment.getProperty("app.report-quality.retrieval.final-top-k"));
        values.put("minSimilarityScore", environment.getProperty("app.report-quality.retrieval.min-similarity-score"));
        values.put("rerankEnabled", environment.getProperty("app.report-quality.retrieval.rerank-enabled"));
        values.put("parentAggregationEnabled", environment.getProperty("app.report-quality.retrieval.parent-aggregation-enabled"));
        return JSON.toJSONString(values);
    }

    /**
     * @Description: 生成默认 runId。
     * @Logic: 使用 UTC 时间戳和短 UUID，便于脚本输出目录和数据库运行记录对齐。
     * @Param: 无。
     * @Return: 默认运行编号。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String generatedRunId() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * @Description: 将 Double 分数转为 BigDecimal。
     * @Logic: null 分数保持 null，避免把未知分数伪装为 0。
     * @Param: value 原始 Double 分数。
     * @Return: BigDecimal 分数或 null。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /**
     * @Description: 提供默认文本。
     * @Logic: 输入为空白时返回默认值，否则返回原始文本。
     * @Param: value 输入文本；defaultValue 默认文本。
     * @Return: 输入文本或默认文本。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * @Description: 将异常信息缩短为可持久化摘要。
     * @Logic: 去除换行并限制长度，避免错误摘要撑爆数据库字段。
     * @Param: ex 运行异常。
     * @Return: 错误摘要。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String shortError(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        String normalized = message.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }

    /**
     * @Description: 将可能为 null 的列表转为空列表。
     * @Logic: 推荐响应部分字段可能为空，统一转空集合减少分支。
     * @Param: values 原始列表。
     * @Return: 原列表或空列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
