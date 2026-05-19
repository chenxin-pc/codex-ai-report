package com.example.aimilvusweb.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @Description: 导入失败记录实体，保存失败文件信息、失败阶段与错误摘要。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:53:07
 */
@Getter
@Setter
public class ReportIngestFailure {

    private Long id;
    private String filename;
    private String title;
    private String source;
    private String institution;
    private String stage;
    private String errorMessage;
    private Instant createdAt;
}
