package com.example.aimilvusweb.common.ocr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class OcrClient {

    private final OcrProperties properties;
    private final RestClient restClient;

    public OcrClient(OcrProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    public boolean isConfigured() {
        return properties.getEndpoint() != null && !properties.getEndpoint().isBlank();
    }

    public OcrRecognizedDocument recognize(MultipartFile file) {
        if (!isConfigured()) {
            throw new IllegalStateException("OCR endpoint is not configured. Set app.ocr.endpoint or OCR_ENDPOINT.");
        }
        String responseBody = requestOcr(file);
        return parseResponse(responseBody);
    }

    private String requestOcr(MultipartFile file) {
        try {
            String filename = file.getOriginalFilename() == null ? "report.pdf" : file.getOriginalFilename();
            ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            String fileField = properties.getFileField() == null || properties.getFileField().isBlank()
                    ? "file"
                    : properties.getFileField();
            body.add(fileField, fileResource);

            RestClient.RequestBodySpec request = restClient.post()
                    .uri(properties.getEndpoint())
                    .contentType(MediaType.MULTIPART_FORM_DATA);
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
            }
            return request.body(body).retrieve().body(String.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read upload file for OCR: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("OCR request failed: " + e.getMessage(), e);
        }
    }

    private OcrRecognizedDocument parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalArgumentException("OCR response is empty");
        }
        JSONObject root;
        try {
            root = JSON.parseObject(responseBody);
        } catch (Exception e) {
            throw new IllegalArgumentException("OCR response is not valid JSON", e);
        }
        OcrRecognizedDocument document = parseObject(root);
        if ((document.fullText() == null || document.fullText().isBlank()) && document.pages().isEmpty()) {
            throw new IllegalArgumentException("OCR response does not contain recognized text");
        }
        return document;
    }

    private OcrRecognizedDocument parseObject(JSONObject object) {
        List<OcrRecognizedDocument.OcrPage> pages = parsePages(object.getJSONArray("pages"));
        String fullText = firstText(object, "fullText", "text", "content", "markdown");

        Object result = object.get("result");
        if ((fullText == null || fullText.isBlank()) && result instanceof String resultText) {
            fullText = resultText;
        }
        if ((fullText == null || fullText.isBlank()) && pages.isEmpty() && result instanceof JSONObject resultObject) {
            return parseObject(resultObject);
        }

        Object data = object.get("data");
        if ((fullText == null || fullText.isBlank()) && pages.isEmpty() && data instanceof JSONObject dataObject) {
            return parseObject(dataObject);
        }
        return new OcrRecognizedDocument(fullText, pages);
    }

    private List<OcrRecognizedDocument.OcrPage> parsePages(JSONArray pagesJson) {
        if (pagesJson == null || pagesJson.isEmpty()) {
            return List.of();
        }
        List<OcrRecognizedDocument.OcrPage> pages = new ArrayList<>();
        for (int i = 0; i < pagesJson.size(); i++) {
            JSONObject page = pagesJson.getJSONObject(i);
            if (page == null) {
                continue;
            }
            String text = firstText(page, "text", "content", "markdown");
            if (text == null || text.isBlank()) {
                continue;
            }
            Integer pageNumberValue = page.getInteger("pageNumber");
            if (pageNumberValue == null) {
                pageNumberValue = page.getInteger("page");
            }
            int pageNumber = pageNumberValue == null ? i + 1 : pageNumberValue;
            pages.add(new OcrRecognizedDocument.OcrPage(pageNumber, text));
        }
        return pages;
    }

    private String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.getString(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
