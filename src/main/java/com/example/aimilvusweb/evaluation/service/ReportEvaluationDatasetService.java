package com.example.aimilvusweb.evaluation.service;

import com.example.aimilvusweb.entity.ReportChunk;
import com.example.aimilvusweb.entity.ReportChunkTag;
import com.example.aimilvusweb.entity.ReportDocument;
import com.example.aimilvusweb.evaluation.dto.EvaluationCaseBundleDTO;
import com.example.aimilvusweb.evaluation.entity.EvaluationCase;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseAnchor;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseForbiddenContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseReferenceContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationCorpus;
import com.example.aimilvusweb.evaluation.entity.EvaluationCorpusReport;
import com.example.aimilvusweb.evaluation.entity.EvaluationReferenceContext;
import com.example.aimilvusweb.evaluation.repository.EvaluationDatasetMapper;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ReportDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * @Description: 研报 RAG 评测数据集服务，负责 corpus、case、标准锚点、标准证据和禁止项的写入与聚合查询。
 * @Logic: 业务报告和 chunk 只作为快照来源，服务将判卷必要字段复制到 eval 域，并用事务保护多表一致写入。
 * @Param: 无。
 * @Return: 评测数据集领域对象或聚合 DTO。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Service
@RequiredArgsConstructor
public class ReportEvaluationDatasetService {

    /** 默认语料版本，调用方未传入时用于创建 core-v1 之类的初始 corpus。 */
    private static final String DEFAULT_CORPUS_VERSION = "v1";
    /** 默认词典版本，保证评测快照即使缺少配置也有明确版本标记。 */
    private static final String DEFAULT_DICTIONARY_VERSION = "v1";
    /** 默认启用状态，表示语料和 case 可参与评测。 */
    private static final String STATUS_ACTIVE = "ACTIVE";
    /** 标准证据默认类型，表示从 CHILD chunk 冻结。 */
    private static final String DEFAULT_CONTEXT_TYPE = "CHILD";

    /** 评测数据集 Mapper，负责 eval 数据集相关表访问。 */
    private final EvaluationDatasetMapper evaluationDatasetMapper;
    /** 研报主档 Mapper，用于从业务域读取报告快照来源。 */
    private final ReportDocumentMapper reportDocumentMapper;
    /** 研报切片 Mapper，用于从业务域读取标准证据候选。 */
    private final ReportChunkMapper reportChunkMapper;
    /** 切片标签 Mapper，用于冻结标准证据的结构化标签快照。 */
    private final ReportChunkTagMapper reportChunkTagMapper;

    /**
     * @Description: 创建评测语料集。
     * @Logic: 校验 corpusCode 和 name 后写入 eval_corpus；版本、词典版本和状态缺失时使用默认值；写入时间统一使用当前 Instant。
     * @Param: corpusCode 语料编码；name 名称；description 说明；corpusVersion 语料版本；dictionaryVersion 词典版本；embeddingModel 向量模型。
     * @Return: 已写入并回填主键的评测语料集。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    @Transactional
    public EvaluationCorpus createCorpus(String corpusCode,
                                         String name,
                                         String description,
                                         String corpusVersion,
                                         String dictionaryVersion,
                                         String embeddingModel) {
        // 校验外部稳定标识，避免生成无法复用的匿名语料。
        requireText(corpusCode, "corpusCode");
        // 校验展示名称，便于脚本和人工报告识别语料用途。
        requireText(name, "name");
        // 使用同一个时间戳写入创建和更新时间，保证快照语义一致。
        Instant now = Instant.now();
        EvaluationCorpus corpus = new EvaluationCorpus();
        // 写入必填和可选语料元数据。
        corpus.setCorpusCode(corpusCode.trim());
        corpus.setName(name.trim());
        corpus.setDescription(trimToNull(description));
        corpus.setCorpusVersion(defaultText(corpusVersion, DEFAULT_CORPUS_VERSION));
        corpus.setDictionaryVersion(defaultText(dictionaryVersion, DEFAULT_DICTIONARY_VERSION));
        corpus.setEmbeddingModel(trimToNull(embeddingModel));
        corpus.setStatus(STATUS_ACTIVE);
        corpus.setCreatedAt(now);
        corpus.setUpdatedAt(now);
        // 通过 Mapper 持久化并依赖数据库唯一索引阻止重复版本。
        evaluationDatasetMapper.insertCorpus(corpus);
        return corpus;
    }

    /**
     * @Description: 将业务报告加入评测语料并冻结报告元数据快照。
     * @Logic: 按 reportId 查询业务报告，未命中抛出明确异常；命中后复制标题、来源、机构、发布日期和文件指纹。
     * @Param: corpusId 评测语料主键；reportId 业务报告主键；reportFingerprint 文件指纹。
     * @Return: 已写入并回填主键的报告快照。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    @Transactional
    public EvaluationCorpusReport addReportSnapshot(Long corpusId, Long reportId, String reportFingerprint) {
        // 校验主键，避免产生无法关联语料或业务报告的快照。
        requireId(corpusId, "corpusId");
        requireId(reportId, "reportId");
        // 从业务主档读取当前事实，评测域只保存本次复制的快照。
        ReportDocument report = reportDocumentMapper.selectById(reportId);
        if (report == null) {
            throw new IllegalArgumentException("Report not found: " + reportId);
        }
        EvaluationCorpusReport corpusReport = new EvaluationCorpusReport();
        // 冻结判卷和排障需要的最小报告元数据。
        corpusReport.setCorpusId(corpusId);
        corpusReport.setReportId(reportId);
        corpusReport.setReportFingerprint(trimToNull(reportFingerprint));
        corpusReport.setTitleSnapshot(report.getTitle());
        corpusReport.setSourceSnapshot(report.getSource());
        corpusReport.setInstitutionSnapshot(report.getInstitution());
        corpusReport.setPublishDateSnapshot(report.getPublishDate());
        corpusReport.setCreatedAt(Instant.now());
        evaluationDatasetMapper.insertCorpusReport(corpusReport);
        return corpusReport;
    }

    /**
     * @Description: 从业务 chunk 创建标准证据快照。
     * @Logic: 按 chunkUid 查询 chunk 和标签，复制定位字段、文本和标签摘要；业务 chunk 后续变化不会回写该 reference context。
     * @Param: corpusId 评测语料主键；chunkUid 来源 chunk 唯一标识；contextType 标准证据类型。
     * @Return: 已写入并回填主键的标准证据。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    @Transactional
    public EvaluationReferenceContext createReferenceContextFromChunk(Long corpusId, String chunkUid, String contextType) {
        // 校验输入，保证标准证据能明确追溯到语料和 chunk。
        requireId(corpusId, "corpusId");
        requireText(chunkUid, "chunkUid");
        // 读取业务 chunk，缺失时直接失败，避免冻结空证据。
        ReportChunk chunk = reportChunkMapper.selectByChunkUid(chunkUid);
        if (chunk == null) {
            throw new IllegalArgumentException("Chunk not found: " + chunkUid);
        }
        // 读取 chunk 标签并按类型折叠为快照文本。
        List<ReportChunkTag> tags = reportChunkTagMapper.selectByChunkUid(chunkUid);
        EvaluationReferenceContext referenceContext = new EvaluationReferenceContext();
        // 写入 chunk 定位、页码范围和正文快照。
        referenceContext.setCorpusId(corpusId);
        referenceContext.setReportId(chunk.getReportId());
        referenceContext.setChunkUid(chunk.getChunkUid());
        referenceContext.setParentChunkUid(chunk.getParentChunkUid());
        referenceContext.setContextType(defaultText(contextType, DEFAULT_CONTEXT_TYPE));
        referenceContext.setSectionPath(chunk.getSectionPath());
        referenceContext.setPageStart(resolvePageStart(chunk));
        referenceContext.setPageEnd(resolvePageEnd(chunk));
        referenceContext.setReferenceText(chunk.getChunkText());
        // 写入结构化标签快照，供主题覆盖和污染证据判分。
        referenceContext.setThemeCodes(joinTagCodes(tags, "THEME"));
        referenceContext.setIndustryCodes(joinTagCodes(tags, "INDUSTRY"));
        referenceContext.setCompanyNames(joinTagCodes(tags, "COMPANY"));
        referenceContext.setTickers(joinTagCodes(tags, "TICKER"));
        referenceContext.setCreatedAt(Instant.now());
        evaluationDatasetMapper.insertReferenceContext(referenceContext);
        return referenceContext;
    }

    /**
     * @Description: 创建 eval case 及其标准锚点、标准证据关联和禁止命中项。
     * @Logic: 先写 case 主体并回填主键，再为子对象补 casePkId 后批量插入；所有写操作在同一事务内完成。
     * @Param: evaluationCase case 主体；anchors 标准锚点；referenceLinks 标准证据关联；forbiddenContexts 禁止命中项。
     * @Return: 已写入并回填主键的 eval case。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    @Transactional
    public EvaluationCase createCase(EvaluationCase evaluationCase,
                                     List<EvaluationCaseAnchor> anchors,
                                     List<EvaluationCaseReferenceContext> referenceLinks,
                                     List<EvaluationCaseForbiddenContext> forbiddenContexts) {
        // 校验 case 主体和关键字段，避免后续运行无法定位 query。
        if (evaluationCase == null) {
            throw new IllegalArgumentException("evaluationCase must not be null");
        }
        requireId(evaluationCase.getCorpusId(), "corpusId");
        requireText(evaluationCase.getCaseId(), "caseId");
        requireText(evaluationCase.getQueryText(), "queryText");
        // 设置默认状态和时间戳，使脚本可最小参数创建 case。
        Instant now = Instant.now();
        if (evaluationCase.getEnabled() == null) {
            evaluationCase.setEnabled(Boolean.TRUE);
        }
        evaluationCase.setCreatedAt(now);
        evaluationCase.setUpdatedAt(now);
        evaluationDatasetMapper.insertCase(evaluationCase);
        // 将标准锚点写入子表。
        for (EvaluationCaseAnchor anchor : safeList(anchors)) {
            anchor.setCasePkId(evaluationCase.getId());
            anchor.setCreatedAt(now);
            evaluationDatasetMapper.insertCaseAnchor(anchor);
        }
        // 将标准证据关联写入子表。
        for (EvaluationCaseReferenceContext referenceLink : safeList(referenceLinks)) {
            referenceLink.setCasePkId(evaluationCase.getId());
            referenceLink.setCreatedAt(now);
            evaluationDatasetMapper.insertCaseReferenceContext(referenceLink);
        }
        // 将禁止命中规则写入子表。
        for (EvaluationCaseForbiddenContext forbiddenContext : safeList(forbiddenContexts)) {
            forbiddenContext.setCasePkId(evaluationCase.getId());
            forbiddenContext.setCreatedAt(now);
            evaluationDatasetMapper.insertCaseForbiddenContext(forbiddenContext);
        }
        return evaluationCase;
    }

    /**
     * @Description: 查询 corpus 下启用的 eval case 聚合视图。
     * @Logic: 先查询启用 case，再逐条补充标准锚点、标准证据和禁止项；空子集合返回空列表。
     * @Param: corpusId 评测语料主键。
     * @Return: Eval case 聚合 DTO 列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    public List<EvaluationCaseBundleDTO> listEnabledCaseBundles(Long corpusId) {
        // 校验 corpusId，避免误查全量 case。
        requireId(corpusId, "corpusId");
        List<EvaluationCase> cases = evaluationDatasetMapper.selectEnabledCasesByCorpusId(corpusId);
        List<EvaluationCaseBundleDTO> bundles = new ArrayList<>(cases.size());
        // 逐条组装聚合视图，保持与数据库返回顺序一致。
        for (EvaluationCase evaluationCase : cases) {
            bundles.add(loadCaseBundle(evaluationCase));
        }
        return bundles;
    }

    /**
     * @Description: 查询单条 eval case 聚合视图。
     * @Logic: 根据 case 主体读取锚点、标准证据和禁止项，供运行服务和导出逻辑复用。
     * @Param: evaluationCase case 主体。
     * @Return: Eval case 聚合 DTO。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    public EvaluationCaseBundleDTO loadCaseBundle(EvaluationCase evaluationCase) {
        // 对外显式拒绝空 case，避免运行服务出现 NPE。
        if (evaluationCase == null || evaluationCase.getId() == null) {
            throw new IllegalArgumentException("evaluationCase and id must not be null");
        }
        // 分别读取子表数据，Mapper 保证未命中时返回空集合。
        List<EvaluationCaseAnchor> anchors = evaluationDatasetMapper.selectAnchorsByCasePkId(evaluationCase.getId());
        List<EvaluationReferenceContext> referenceContexts = evaluationDatasetMapper.selectReferenceContextsByCasePkId(evaluationCase.getId());
        List<EvaluationCaseForbiddenContext> forbiddenContexts = evaluationDatasetMapper.selectForbiddenContextsByCasePkId(evaluationCase.getId());
        return new EvaluationCaseBundleDTO(evaluationCase, anchors, referenceContexts, forbiddenContexts);
    }

    /**
     * @Description: 关联 eval case 与标准证据。
     * @Logic: 写入相关等级和 required 标记；调用方可在 case 创建后追加标准证据。
     * @Param: casePkId eval_case 主键；referenceContextId 标准证据主键；relevanceLevel 相关等级；required 是否必需；notes 备注。
     * @Return: 已写入并回填主键的关联对象。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    @Transactional
    public EvaluationCaseReferenceContext linkReferenceContext(Long casePkId,
                                                               Long referenceContextId,
                                                               Integer relevanceLevel,
                                                               Boolean required,
                                                               String notes) {
        // 校验两侧主键，保证关联可追溯。
        requireId(casePkId, "casePkId");
        requireId(referenceContextId, "referenceContextId");
        EvaluationCaseReferenceContext link = new EvaluationCaseReferenceContext();
        // 写入关联元数据并使用默认相关等级兜底。
        link.setCasePkId(casePkId);
        link.setReferenceContextId(referenceContextId);
        link.setRelevanceLevel(relevanceLevel == null ? 1 : relevanceLevel);
        link.setRequired(required == null || required);
        link.setNotes(trimToNull(notes));
        link.setCreatedAt(Instant.now());
        evaluationDatasetMapper.insertCaseReferenceContext(link);
        return link;
    }

    /**
     * @Description: 校验字符串参数非空。
     * @Logic: null 或空白字符串都会抛出 IllegalArgumentException，避免持久化无效标识。
     * @Param: value 待校验文本；fieldName 字段名。
     * @Return: 无（仅在非法时抛出异常）。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    /**
     * @Description: 校验主键参数有效。
     * @Logic: null 或小于等于 0 的主键都会抛出 IllegalArgumentException，阻止写入孤儿快照。
     * @Param: value 待校验主键；fieldName 字段名。
     * @Return: 无（仅在非法时抛出异常）。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private void requireId(Long value, String fieldName) {
        if (value == null || value <= 0L) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    /**
     * @Description: 提供默认文本。
     * @Logic: 输入为空白时返回默认值，否则返回去除首尾空白后的输入。
     * @Param: value 输入文本；defaultValue 默认文本。
     * @Return: 规范化后的文本。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * @Description: 将空白文本规范化为 null。
     * @Logic: 便于数据库区分未提供值和有意义的空格输入。
     * @Param: value 输入文本。
     * @Return: 非空文本或 null。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * @Description: 解析 chunk 起始页码。
     * @Logic: 优先使用 startPageNumber，缺失时退回 pageNumber。
     * @Param: chunk 业务 chunk。
     * @Return: 起始页码或 null。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private Integer resolvePageStart(ReportChunk chunk) {
        return chunk.getStartPageNumber() == null ? chunk.getPageNumber() : chunk.getStartPageNumber();
    }

    /**
     * @Description: 解析 chunk 结束页码。
     * @Logic: 优先使用 endPageNumber，缺失时退回 pageNumber。
     * @Param: chunk 业务 chunk。
     * @Return: 结束页码或 null。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private Integer resolvePageEnd(ReportChunk chunk) {
        return chunk.getEndPageNumber() == null ? chunk.getPageNumber() : chunk.getEndPageNumber();
    }

    /**
     * @Description: 按标签类型拼接标签编码。
     * @Logic: 使用 LinkedHashSet 保持原始顺序并去重，标签类型比较大小写不敏感。
     * @Param: tags 标签列表；tagType 目标标签类型。
     * @Return: 逗号分隔标签编码，未命中时返回空字符串。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private String joinTagCodes(List<ReportChunkTag> tags, String tagType) {
        Set<String> values = new LinkedHashSet<>();
        // 遍历标签并收集目标类型的非空编码。
        for (ReportChunkTag tag : safeList(tags)) {
            if (tag.getTagType() != null
                    && tag.getTagType().toUpperCase(Locale.ROOT).equals(tagType)
                    && tag.getTagCode() != null
                    && !tag.getTagCode().isBlank()) {
                values.add(tag.getTagCode().trim());
            }
        }
        return String.join(",", values);
    }

    /**
     * @Description: 将可能为 null 的列表转为空列表。
     * @Logic: 避免调用方传入 null 时分支散落在业务流程中。
     * @Param: values 原始列表。
     * @Return: 原列表或空列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
