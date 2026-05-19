package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportIngestStageEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
/**
 * @Description: ReportIngestStageEventMapper类，负责导入阶段事件的写入与查询。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:15:00
 */
public interface ReportIngestStageEventMapper {

    int insert(ReportIngestStageEvent event);

    List<ReportIngestStageEvent> selectTimelineByReportId(@Param("reportId") Long reportId);

    List<ReportIngestStageEvent> selectByTitleKeyword(@Param("keyword") String keyword, @Param("limit") int limit);
}
