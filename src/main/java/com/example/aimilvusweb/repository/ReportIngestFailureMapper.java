package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportIngestFailure;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
/**
 * @Description: ReportIngestFailureMapper类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public interface ReportIngestFailureMapper {

    /**
     * @Description: 执行insert相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    int insert(ReportIngestFailure reportIngestFailure);

    /**
     * @Description: 执行selectRecent相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    List<ReportIngestFailure> selectRecent(int limit);
}
