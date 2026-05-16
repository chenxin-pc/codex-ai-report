package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportChunk;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ReportChunkMapper {

    int insert(ReportChunk reportChunk);

    ReportChunk selectByChunkUid(String chunkUid);

    List<ReportChunk> selectByReportId(Long reportId);

    List<ReportChunk> selectChildrenByParentChunkUid(String parentChunkUid);
}
