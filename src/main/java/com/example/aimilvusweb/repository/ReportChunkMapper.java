package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportChunk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReportChunkMapper {

    int insert(ReportChunk reportChunk);
}
