package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportOcrPage;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ReportOcrPageMapper {

    int insert(ReportOcrPage reportOcrPage);

    List<ReportOcrPage> selectByReportId(Long reportId);
}
