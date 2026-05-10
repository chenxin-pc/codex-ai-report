package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ReportDocument;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReportDocumentMapper {

    int insert(ReportDocument reportDocument);
}
