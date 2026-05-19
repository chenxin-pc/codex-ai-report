package com.example.aimilvusweb.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Description: 导入异步调度配置，聚合轮询频率、批量大小、重试与落盘目录等参数。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-19 23:15:00
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.report-ingest-async")
public class ReportIngestAsyncProperties {

    /** 定时任务固定轮询间隔（毫秒）。 */
    private long fixedDelayMs = 3000L;
    /** 单次调度批量拉取任务上限。 */
    private int batchSize = 5;
    /** 单阶段最大重试次数。 */
    private int maxAttempts = 3;
    /** 第一次重试退避时长（毫秒）。 */
    private long backoffFirstMs = 60_000L;
    /** 第二次重试退避时长（毫秒）。 */
    private long backoffSecondMs = 300_000L;
    /** 第三次及以后重试退避时长（毫秒）。 */
    private long backoffThirdMs = 900_000L;
    /** 上传文件暂存目录。 */
    private String spoolDir = "reports/ingest-spool";
}
