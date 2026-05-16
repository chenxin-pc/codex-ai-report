package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportParagraphAtom;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ReportParagraphAtomMapper {

    int insert(ReportParagraphAtom reportParagraphAtom);

    List<ReportParagraphAtom> selectByReportId(Long reportId);
}
