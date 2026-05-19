package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.IngestJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
/**
 * @Description: IngestJobMapper类，负责异步导入任务的增删改查与调度取数。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:15:00
 */
public interface IngestJobMapper {

    int insert(IngestJob job);

    IngestJob selectByJobUid(@Param("jobUid") String jobUid);

    List<IngestJob> selectRunnableForStage(@Param("stage") String stage,
                                           @Param("status") String status,
                                           @Param("nextRunAt") Instant nextRunAt,
                                           @Param("limit") int limit);

    int updateStatusAndAttempt(IngestJob job);

    int bindReportId(@Param("jobUid") String jobUid, @Param("reportId") Long reportId, @Param("updatedAt") Instant updatedAt);

    int countByStageStatus(@Param("stage") String stage, @Param("status") String status);
}
