package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportChunkDiagnostic;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
/**
 * @Description: ReportChunkDiagnosticMapper类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public interface ReportChunkDiagnosticMapper {

    /**
     * @Description: 执行insert相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    int insert(ReportChunkDiagnostic reportChunkDiagnostic);

    /**
     * @Description: 执行selectByReportId相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    List<ReportChunkDiagnostic> selectByReportId(Long reportId);
}
