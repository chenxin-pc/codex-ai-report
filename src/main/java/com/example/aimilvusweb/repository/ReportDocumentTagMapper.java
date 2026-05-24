package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportDocumentTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Description: 报告级结构化标签 Mapper，负责 report_document_tag 的写入、查询和诊断。
 * @Logic: 报告级标签作为父标签主数据，用于 metadata 同步和查询集合约束。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 受影响行数或标签列表。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Mapper
public interface ReportDocumentTagMapper {

    /**
     * @Description: 插入单条报告级标签。
     * @Logic: 由报告级标签聚合或导入元数据写入父标签结果。
     * @Param: tag 标签实体。
     * @Return: 受影响行数。
     */
    int insert(ReportDocumentTag tag);

    /**
     * @Description: 删除指定报告在指定词库版本下的旧标签。
     * @Logic: 重算父标签前清理旧结果，避免同版本历史结果混用。
     * @Param: reportId 研报 ID；dictionaryVersion 词库版本。
     * @Return: 受影响行数。
     */
    int deleteByReportIdAndVersion(@Param("reportId") Long reportId, @Param("dictionaryVersion") String dictionaryVersion);

    /**
     * @Description: 删除指定报告、版本和来源的旧标签。
     * @Logic: 支持导入元数据标签和 chunk 聚合标签分别覆盖，避免互相擦除。
     * @Param: reportId 研报 ID；dictionaryVersion 词库版本；source 标签来源。
     * @Return: 受影响行数。
     */
    int deleteByReportIdVersionAndSource(@Param("reportId") Long reportId,
                                         @Param("dictionaryVersion") String dictionaryVersion,
                                         @Param("source") String source);

    /**
     * @Description: 查询指定报告的全部报告级标签。
     * @Logic: 用于构建向量 metadata 中的报告级父标签摘要。
     * @Param: reportId 研报 ID。
     * @Return: 标签列表。
     */
    List<ReportDocumentTag> selectByReportId(Long reportId);

    /**
     * @Description: 判断指定类型和编码的报告级标签是否存在。
     * @Logic: Milvus metadata 无结果时用于诊断父标签主数据是否已有对应报告集合。
     * @Param: tagType 标签类型；tagCodes 标签编码列表。
     * @Return: 命中数量。
     */
    int countByTagCodes(@Param("tagType") String tagType, @Param("tagCodes") List<String> tagCodes);
}
