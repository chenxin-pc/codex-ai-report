package com.example.aimilvusweb.common.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ocr")
public class OcrProperties {

    private String endpoint = "";
    private String apiKey = "";
    private String fileField = "file";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getFileField() {
        return fileField;
    }

    public void setFileField(String fileField) {
        this.fileField = fileField;
    }
}
