package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
/**
 * @Description: ReportChunkMapper类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public interface ReportChunkMapper {

    /**
     * @Description: 执行insert相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    int insert(ReportChunk reportChunk);

    /**
     * @Description: 执行selectByChunkUid相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    ReportChunk selectByChunkUid(String chunkUid);

    /**
     * @Description: 执行selectByReportId相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    List<ReportChunk> selectByReportId(Long reportId);

    /**
     * @Description: 执行selectChildrenByParentChunkUid相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    List<ReportChunk> selectChildrenByParentChunkUid(String parentChunkUid);

    /**
     * @Description: 更新切片向量入库状态，供异步向量阶段幂等标记使用。
     * @Logic: 通过 chunkUid 精确定位单条切片，更新 vectorStored 布尔值。
     * @Param: chunkUid 切片唯一标识；vectorStored 最新向量状态。
     * @Return: 受影响行数。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-19 23:15:00
     */
    int updateVectorStoredByChunkUid(@Param("chunkUid") String chunkUid, @Param("vectorStored") boolean vectorStored);
}
