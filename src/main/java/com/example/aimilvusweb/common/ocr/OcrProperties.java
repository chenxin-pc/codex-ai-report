package com.example.aimilvusweb.common.ocr;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Description: OcrProperties类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "app.ocr")
public class OcrProperties {

    /**
     * OCR 服务调用地址，支持通用 OCR 接口或 DashScope 兼容端点。
     */
    private String endpoint = "";
    /**
     * OCR 服务鉴权密钥，用于外部 OCR 请求认证。
     */
    private String apiKey = "";
    /**
     * 上传文件的表单字段名，需与目标 OCR 服务接口约定保持一致，默认值为 file。
     */
    private String fileField = "file";
    /**
     * OCR 调用使用的模型标识，默认值为 qwen-vl-ocr-latest。
     */
    private String model = "qwen-vl-ocr-latest";
    /**
     * OCR 任务类型标识，默认采用 document_parsing 以解析研报版面文本。
     */
    private String task = "document_parsing";
    /**
     * PDF 渲染 DPI，控制页图像清晰度与体积，默认值 160 用于平衡识别质量与性能。
     */
    private int renderDpi = 160;
}
