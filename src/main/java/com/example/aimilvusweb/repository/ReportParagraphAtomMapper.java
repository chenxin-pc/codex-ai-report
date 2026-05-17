package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportParagraphAtom;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
/**
 * @Description: ReportParagraphAtomMapper类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public interface ReportParagraphAtomMapper {

    /**
     * @Description: 执行insert相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    int insert(ReportParagraphAtom reportParagraphAtom);

    /**
     * @Description: 执行selectByReportId相关业务处理。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    List<ReportParagraphAtom> selectByReportId(Long reportId);
}
