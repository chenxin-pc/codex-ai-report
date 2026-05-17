package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportDocument;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/**
 * @Description: ReportDocumentMapper类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public interface ReportDocumentMapper {

    /**
     * @Description: 执行insert相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    int insert(ReportDocument reportDocument);
}
