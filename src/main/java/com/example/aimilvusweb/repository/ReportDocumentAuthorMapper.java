package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportDocumentAuthor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Description: 研报作者 Mapper，负责作者元数据的写入、查询和清理。
 * @Logic: 导入阶段按 reportId 写入作者集合，向量阶段按 reportId 读取并投影到 Milvus。
 * @Param: 详见各方法入参。
 * @Return: 作者实体、受影响行数或作者列表。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
@Mapper
public interface ReportDocumentAuthorMapper {

    /**
     * @Description: 插入单个研报作者。
     * @Logic: 使用 reportId、authorName、normalizedAuthorName 和 authorOrder 保存作者事实数据。
     * @Param: author 待保存作者实体。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int insert(ReportDocumentAuthor author);

    /**
     * @Description: 查询指定研报的作者列表。
     * @Logic: 按 authorOrder 和 id 稳定排序，保证多作者投影顺序可复现。
     * @Param: reportId 研报主档 ID。
     * @Return: 作者实体列表；无作者时返回空列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    List<ReportDocumentAuthor> selectByReportId(@Param("reportId") Long reportId);

    /**
     * @Description: 删除指定研报的作者列表。
     * @Logic: OCR 阶段重试或显式重建作者时先清理旧作者，避免重复元数据。
     * @Param: reportId 研报主档 ID。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteByReportId(@Param("reportId") Long reportId);
}
