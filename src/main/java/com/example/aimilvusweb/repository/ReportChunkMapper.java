package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportChunk;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
/**
 * @Description: ReportChunkMapper类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public interface ReportChunkMapper {

    /**
     * @Description: 执行insert相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    int insert(ReportChunk reportChunk);

    /**
     * @Description: 执行selectByChunkUid相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    ReportChunk selectByChunkUid(String chunkUid);

    /**
     * @Description: 执行selectByReportId相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    List<ReportChunk> selectByReportId(Long reportId);

    /**
     * @Description: 执行selectChildrenByParentChunkUid相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    List<ReportChunk> selectChildrenByParentChunkUid(String parentChunkUid);
}
