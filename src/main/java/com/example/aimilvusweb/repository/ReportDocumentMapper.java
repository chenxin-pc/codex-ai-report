package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportDocument;
import com.example.aimilvusweb.dto.ReportObservationRespDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
/**
 * @Description: ReportDocumentMapper类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public interface ReportDocumentMapper {

    /**
     * @Description: 执行insert相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    int insert(ReportDocument reportDocument);

    /**
     * @Description: 按主键查询研报主档，供异步阶段补全向量元数据。
     * @Logic: 使用 reportId 精确命中单条记录，未命中返回 null。
     * @Param: reportId 研报主键。
     * @Return: 研报主档实体或 null。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    ReportDocument selectById(Long reportId);

    /**
     * @Description: 查询研报观测列表，返回研报主档及最新任务状态。
     * @Logic: 按标题关键词筛选并按 report_id 倒序返回，单条研报拼接最新 ingest_job 状态。
     * @Param: titleKeyword 标题关键词；limit 返回数量上限。
     * @Return: 研报观测列表。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:59:00
     */
    List<ReportObservationRespDTO> selectObservations(@Param("titleKeyword") String titleKeyword, @Param("limit") int limit);
}
