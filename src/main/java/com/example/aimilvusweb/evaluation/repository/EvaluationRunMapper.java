package com.example.aimilvusweb.evaluation.repository;

import com.example.aimilvusweb.evaluation.entity.EvaluationCaseRun;
import com.example.aimilvusweb.evaluation.entity.EvaluationRetrievedContext;
import com.example.aimilvusweb.evaluation.entity.EvaluationRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * @Description: 评测运行 MyBatis Mapper，负责 eval run、case run 和 retrieved context 的持久化访问。
 * @Logic: 所有 SQL 通过 XML 参数绑定执行，运行服务用这些方法记录快照、响应和自动判分。
 * @Param: 各方法入参为运行实体、case run 实体、检索上下文或状态字段。
 * @Return: 插入行数、更新行数或查询结果列表。
 * @author: cx
 * @Date: 2026-06-03 10:00:00
 */
@Mapper
public interface EvaluationRunMapper {

    /**
     * @Description: 新增 eval run 并回填主键。
     * @Logic: 写入模型、prompt、词典和检索配置快照，作为 case run 的父记录。
     * @Param: evaluationRun 待新增评测运行。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int insertRun(EvaluationRun evaluationRun);

    /**
     * @Description: 按 runId 查询 eval run。
     * @Logic: 使用外部稳定运行编号查询，未命中返回 null。
     * @Param: runId 外部运行编号。
     * @Return: eval run 实体或 null。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    EvaluationRun selectRunByRunId(String runId);

    /**
     * @Description: 更新 eval run 状态和结束信息。
     * @Logic: 运行完成或失败时写入 status、finishedAt 和 errorSummary。
     * @Param: id eval_run 主键；status 最新状态；finishedAt 结束时间；errorSummary 错误摘要。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int updateRunStatus(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("finishedAt") Instant finishedAt,
                        @Param("errorSummary") String errorSummary);

    /**
     * @Description: 新增 eval case run 并回填主键。
     * @Logic: Case 执行开始前写入 RUNNING 状态，执行后再更新结果或失败原因。
     * @Param: caseRun 待新增 case run。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int insertCaseRun(EvaluationCaseRun caseRun);

    /**
     * @Description: 更新成功 case run 的响应、质量和自动判分。
     * @Logic: 推荐链路返回后一次性写入输出文本、证据质量、指标布尔值和完成时间。
     * @Param: caseRun 包含待更新字段的 case run。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int updateCaseRunResult(EvaluationCaseRun caseRun);

    /**
     * @Description: 更新失败 case run。
     * @Logic: 推荐接口或判分异常时写入 FAILED 状态、错误摘要和结束时间。
     * @Param: id case run 主键；errorMessage 错误摘要；finishedAt 结束时间。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int updateCaseRunFailure(@Param("id") Long id,
                             @Param("errorMessage") String errorMessage,
                             @Param("finishedAt") Instant finishedAt);

    /**
     * @Description: 查询某次 eval run 下的 case run 列表。
     * @Logic: 按 case run id 升序返回，供 Ragas 导出和报告生成读取。
     * @Param: evalRunId eval_run 表主键。
     * @Return: case run 列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    List<EvaluationCaseRun> selectCaseRunsByEvalRunId(Long evalRunId);

    /**
     * @Description: 新增检索上下文记录。
     * @Logic: 将推荐 Top 证据或诊断候选保存为 FINAL、INITIAL、FILTERED 或 DIAGNOSTIC 阶段。
     * @Param: context 待新增检索上下文。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    int insertRetrievedContext(EvaluationRetrievedContext context);

    /**
     * @Description: 查询某个 case run 的检索上下文。
     * @Logic: 按阶段和 rank 排序，供自动判分和 Ragas 导出使用。
     * @Param: caseRunId case run 主键。
     * @Return: 检索上下文列表。
     * @author: cx
     * @Date: 2026-06-03 10:00:00
     */
    List<EvaluationRetrievedContext> selectRetrievedContextsByCaseRunId(Long caseRunId);
}
