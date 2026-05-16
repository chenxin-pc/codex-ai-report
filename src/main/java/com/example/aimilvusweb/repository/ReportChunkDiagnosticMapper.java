package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportChunkDiagnostic;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ReportChunkDiagnosticMapper {

    int insert(ReportChunkDiagnostic reportChunkDiagnostic);

    List<ReportChunkDiagnostic> selectByReportId(Long reportId);
}
