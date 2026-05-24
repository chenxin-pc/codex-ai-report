package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportChunkTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Description: 切片结构化标签 Mapper，负责标签主数据的写入、查询和覆盖式重打标。
 * @Logic: 标签抽取服务先删除指定 chunk/version 下旧标签，再批量写入最新词库命中结果。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 受影响行数或标签列表。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Mapper
public interface ReportChunkTagMapper {

    /**
     * @Description: 插入单条 chunk 标签。
     * @Logic: 由标签抽取 job 写入主题、行业、公司和代码命中结果。
     * @Param: tag 标签实体。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    int insert(ReportChunkTag tag);

    /**
     * @Description: 删除指定 chunk 在指定词库版本下的旧标签。
     * @Logic: 重打标前清理旧结果，避免历史标签和新标签混用。
     * @Param: chunkUid 切片唯一标识；dictionaryVersion 词库版本。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    int deleteByChunkUidAndVersion(@Param("chunkUid") String chunkUid, @Param("dictionaryVersion") String dictionaryVersion);

    /**
     * @Description: 查询单个 chunk 的全部标签。
     * @Logic: 用于构建 Milvus metadata 和证据主题覆盖判断。
     * @Param: chunkUid 切片唯一标识。
     * @Return: 标签列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    List<ReportChunkTag> selectByChunkUid(String chunkUid);

    /**
     * @Description: 查询多个 chunk 的全部标签。
     * @Logic: 召回后批量补充标签主数据，避免逐条查询造成 N+1。
     * @Param: chunkUids 切片唯一标识列表。
     * @Return: 标签列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    List<ReportChunkTag> selectByChunkUids(@Param("chunkUids") List<String> chunkUids);

    /**
     * @Description: 查询指定报告的全部 chunk 标签。
     * @Logic: 用于将 chunk 标签聚合为报告级父标签。
     * @Param: reportId 研报 ID。
     * @Return: 标签列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    List<ReportChunkTag> selectByReportId(Long reportId);

    /**
     * @Description: 判断指定类型和编码的标签是否存在。
     * @Logic: Milvus metadata 无结果时用于诊断 MySQL 标签主数据是否已有相关主题。
     * @Param: tagType 标签类型；tagCodes 标签编码列表。
     * @Return: 命中数量。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    int countByTagCodes(@Param("tagType") String tagType, @Param("tagCodes") List<String> tagCodes);
}
