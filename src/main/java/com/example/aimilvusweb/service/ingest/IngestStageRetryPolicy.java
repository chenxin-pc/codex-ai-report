package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.config.ReportIngestAsyncProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * @Description: 入库阶段重试策略，统一判断异常是否可重试并计算退避时间与错误摘要。
 * @Logic: 基于异常消息识别超时、限流和临时服务不可用；按 attempt 映射配置中的三档退避时间。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Component
public class IngestStageRetryPolicy {

    /** 异步导入配置，提供最大尝试次数与退避时间。 */
    private final ReportIngestAsyncProperties properties;

    /**
     * @Description: 初始化重试策略。
     * @Logic: 保存异步配置，供异常判断和退避计算读取。
     * @Param: properties 异步导入配置。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public IngestStageRetryPolicy(ReportIngestAsyncProperties properties) {
        this.properties = properties;
    }

    /**
     * @Description: 判断异常是否可重试。
     * @Logic: 网络超时、限流、502/503 和连接重置视为临时错误，其余异常视为不可重试。
     * @Param: ex 异常对象。
     * @Return: true 表示允许进入退避重试。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public boolean isRetryable(Exception ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("timeout")
                || message.contains("timed out")
                || message.contains("429")
                || message.contains("too many requests")
                || message.contains("503")
                || message.contains("502")
                || message.contains("connection reset");
    }

    /**
     * @Description: 判断当前失败是否还能继续重试。
     * @Logic: 异常必须可重试且当前 attempt 小于配置最大次数，才会回到 PENDING 等待下一轮。
     * @Param: ex 异常对象；attempt 当前阶段尝试次数。
     * @Return: true 表示本次失败后仍可重试。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public boolean canRetry(Exception ex, int attempt) {
        return isRetryable(ex) && attempt < properties.getMaxAttempts();
    }

    /**
     * @Description: 计算重试退避时长。
     * @Logic: 第 1/2 次尝试分别使用 first/second 配置，其余尝试使用 third 配置。
     * @Param: attempt 当前阶段尝试次数。
     * @Return: 退避毫秒值。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public long backoffMs(int attempt) {
        return switch (attempt) {
            case 1 -> properties.getBackoffFirstMs();
            case 2 -> properties.getBackoffSecondMs();
            default -> properties.getBackoffThirdMs();
        };
    }

    /**
     * @Description: 生成错误码。
     * @Logic: 优先使用异常类名，类名为空时回退 UNKNOWN_ERROR。
     * @Param: ex 异常对象。
     * @Return: 错误码。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public String errorCode(Exception ex) {
        String name = ex.getClass().getSimpleName();
        return name == null || name.isBlank() ? "UNKNOWN_ERROR" : name;
    }

    /**
     * @Description: 生成短错误消息。
     * @Logic: 空消息回退为错误码，超长消息截断到 500 字符，避免观测字段过长。
     * @Param: ex 异常对象。
     * @Return: 短错误消息。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public String shortMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return errorCode(ex);
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
