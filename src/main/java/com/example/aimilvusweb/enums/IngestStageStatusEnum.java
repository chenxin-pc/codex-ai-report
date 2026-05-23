package com.example.aimilvusweb.enums;

import java.util.Arrays;

/**
 * @Description: 导入阶段状态枚举，统一维护状态码与中文文案映射。
 * @Logic: 根据状态码返回对应中文文案；未知状态回退为原始状态码，避免前端展示空值。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-20 10:30:00
 */
public enum IngestStageStatusEnum {
    PENDING("PENDING", "待执行"),
    PROCESSING("PROCESSING", "执行中"),
    SUCCEEDED("SUCCEEDED", "已成功"),
    FAILED_RETRYABLE("FAILED_RETRYABLE", "失败（可重试）"),
    FAILED_FINAL("FAILED_FINAL", "失败（最终）");

    private final String code;
    private final String label;

    IngestStageStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static String toLabel(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        return Arrays.stream(values())
                .filter(item -> item.code.equals(code))
                .map(IngestStageStatusEnum::label)
                .findFirst()
                .orElse(code);
    }
}
