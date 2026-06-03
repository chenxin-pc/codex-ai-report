package com.example.aimilvusweb.evaluation.repository;

import com.example.aimilvusweb.evaluation.entity.EvaluationCase;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseAnchor;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseForbiddenContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationCaseReferenceContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationCorpus;
import com.example.aimilvusweb.evaluation.entity.EvaluationCorpusReport;
import com.example.aimilvusweb.evaluation.entity.EvaluationReferenceContext;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Description: 评测数据集 MyBatis Mapper，负责 eval corpus、case、标准证据和禁止项的持久化访问。
 * @Logic: 仅声明参数绑定方法，SQL 统一放在 XML 中，避免业务输入拼接到 SQL。
 * @Param: 各方法入参为评测实体主键、语料编号或待写入实体。
 * @Return: 插入行数、单条实体或实体列表。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Mapper
public interface EvaluationDatasetMapper {

    /**
     * @Description: 新增评测语料集并回填主键。
     * @Logic: 写入 corpusCode、版本、模型和词典快照，唯一性由数据库索引保护。
     * @Param: corpus 待新增评测语料集。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int insertCorpus(EvaluationCorpus corpus);

    /**
     * @Description: 按主键查询评测语料集。
     * @Logic: 使用 id 精确查询，未命中返回 null。
     * @Param: id 评测语料集主键。
     * @Return: 评测语料集实体或 null。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    EvaluationCorpus selectCorpusById(Long id);

    /**
     * @Description: 按语料编码和版本查询评测语料集。
     * @Logic: 用于避免脚本重复创建同一版本 corpus。
     * @Param: corpusCode 语料编码；corpusVersion 语料版本。
     * @Return: 评测语料集实体或 null。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    EvaluationCorpus selectCorpusByCodeVersion(@Param("corpusCode") String corpusCode, @Param("corpusVersion") String corpusVersion);

    /**
     * @Description: 新增语料报告快照。
     * @Logic: 将业务报告元数据复制到 eval_corpus_report，历史快照不跟随业务表自动变化。
     * @Param: corpusReport 待新增报告快照。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int insertCorpusReport(EvaluationCorpusReport corpusReport);

    /**
     * @Description: 查询某个 corpus 下的报告快照列表。
     * @Logic: 按创建顺序返回，供脚本导出和语料复核使用。
     * @Param: corpusId 评测语料集主键。
     * @Return: 报告快照列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    List<EvaluationCorpusReport> selectCorpusReportsByCorpusId(Long corpusId);

    /**
     * @Description: 新增 eval case。
     * @Logic: 保存 query、期望字段和判卷规则，并回填主键。
     * @Param: evaluationCase 待新增 eval case。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int insertCase(EvaluationCase evaluationCase);

    /**
     * @Description: 按主键查询 eval case。
     * @Logic: 使用 eval_case.id 精确查询，未命中返回 null。
     * @Param: id eval case 主键。
     * @Return: eval case 实体或 null。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    EvaluationCase selectCaseById(Long id);

    /**
     * @Description: 查询 corpus 下启用的 eval case。
     * @Logic: 用于 eval run 默认执行范围，按 id 升序保证稳定顺序。
     * @Param: corpusId 评测语料集主键。
     * @Return: 启用的 eval case 列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    List<EvaluationCase> selectEnabledCasesByCorpusId(Long corpusId);

    /**
     * @Description: 新增 eval case 标准锚点。
     * @Logic: 保存主题、行业、公司、股票代码或章节意图的期望锚点。
     * @Param: anchor 待新增标准锚点。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int insertCaseAnchor(EvaluationCaseAnchor anchor);

    /**
     * @Description: 查询 eval case 的标准锚点。
     * @Logic: 按 casePkId 查询并按 id 排序，供自动判分使用。
     * @Param: casePkId eval_case 表主键。
     * @Return: 标准锚点列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    List<EvaluationCaseAnchor> selectAnchorsByCasePkId(Long casePkId);

    /**
     * @Description: 新增标准证据快照。
     * @Logic: 保存 referenceText、chunk 定位和标签快照，并回填主键。
     * @Param: referenceContext 待新增标准证据。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int insertReferenceContext(EvaluationReferenceContext referenceContext);

    /**
     * @Description: 按主键查询标准证据。
     * @Logic: 使用 reference context 主键精确查询，未命中返回 null。
     * @Param: id 标准证据主键。
     * @Return: 标准证据实体或 null。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    EvaluationReferenceContext selectReferenceContextById(Long id);

    /**
     * @Description: 查询 eval case 关联的标准证据。
     * @Logic: 通过 eval_case_reference_context 关联表查询，供召回命中判分和 Ragas 导出使用。
     * @Param: casePkId eval_case 表主键。
     * @Return: 标准证据列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    List<EvaluationReferenceContext> selectReferenceContextsByCasePkId(Long casePkId);

    /**
     * @Description: 新增 eval case 与标准证据的关联。
     * @Logic: 记录相关等级和 required 标记，唯一索引避免重复关联。
     * @Param: link 待新增关联对象。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int insertCaseReferenceContext(EvaluationCaseReferenceContext link);

    /**
     * @Description: 查询 eval case 的标准证据关联。
     * @Logic: 返回关联元数据，供判断 required 证据是否存在。
     * @Param: casePkId eval_case 表主键。
     * @Return: 标准证据关联列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    List<EvaluationCaseReferenceContext> selectCaseReferenceLinksByCasePkId(Long casePkId);

    /**
     * @Description: 新增 eval case 禁止命中项。
     * @Logic: 保存主题、公司、关键词或 chunk 等污染规则，自动判分按类型扫描。
     * @Param: forbiddenContext 待新增禁止命中项。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int insertCaseForbiddenContext(EvaluationCaseForbiddenContext forbiddenContext);

    /**
     * @Description: 查询 eval case 的禁止命中项。
     * @Logic: 按 casePkId 查询所有污染规则，供 retrieved context 和 responseText 判分使用。
     * @Param: casePkId eval_case 表主键。
     * @Return: 禁止命中项列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    List<EvaluationCaseForbiddenContext> selectForbiddenContextsByCasePkId(Long casePkId);
}
