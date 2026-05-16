package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportIngestFailure;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ReportIngestFailureMapper {

    int insert(ReportIngestFailure reportIngestFailure);

    List<ReportIngestFailure> selectRecent(int limit);
}
