package com.example.aimilvusweb.common.ocr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        if (isDashScopeEndpoint()) {
            return recognizeByDashScope(file);
        }
        String responseBody = requestOcr(file);
        return parseResponse(responseBody);
    }

    private boolean isDashScopeEndpoint() {
        String endpoint = properties.getEndpoint() == null ? "" : properties.getEndpoint();
        return endpoint.contains("dashscope.aliyuncs.com");
    }

    private OcrRecognizedDocument recognizeByDashScope(MultipartFile file) {
        List<OcrRecognizedDocument.OcrPage> pages = renderPdfPages(file).stream()
                .map(pageImage -> new OcrRecognizedDocument.OcrPage(
                        pageImage.pageNumber(),
                        requestDashScopePageText(pageImage.dataUrl(), pageImage.pageNumber())
                ))
                .filter(page -> page.text() != null && !page.text().isBlank())
                .toList();
        String fullText = String.join("\n\n", pages.stream()
                .map(OcrRecognizedDocument.OcrPage::text)
                .toList());
        return new OcrRecognizedDocument(fullText, pages);
    }

    private List<PageImage> renderPdfPages(MultipartFile file) {
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFRenderer renderer = new PDFRenderer(document);
            List<PageImage> pages = new ArrayList<>();
            int dpi = Math.max(properties.getRenderDpi(), 96);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                pages.add(new PageImage(i + 1, toJpegDataUrl(image)));
            }
            return pages;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to render PDF pages for OCR: " + e.getMessage(), e);
        }
    }

    private String toJpegDataUrl(BufferedImage image) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(imageOutputStream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.85F);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    private String requestDashScopePageText(String imageDataUrl, int pageNumber) {
        try {
            String responseBody = restClient.post()
                    .uri(resolveDashScopeGenerationEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .body(buildDashScopeRequest(imageDataUrl))
                    .retrieve()
                    .body(String.class);
            String text = parseDashScopeText(responseBody);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("DashScope OCR returned empty text for page " + pageNumber);
            }
            return text;
        } catch (Exception e) {
            throw new IllegalStateException("DashScope OCR request failed on page " + pageNumber + ": " + e.getMessage(), e);
        }
    }

    private String resolveDashScopeGenerationEndpoint() {
        String endpoint = properties.getEndpoint().trim();
        if (endpoint.endsWith("/services/aigc/multimodal-generation/generation")) {
            return endpoint;
        }
        return endpoint.replaceAll("/+$", "") + "/services/aigc/multimodal-generation/generation";
    }

    private Map<String, Object> buildDashScopeRequest(String imageDataUrl) {
        return Map.of(
                "model", properties.getModel(),
                "input", Map.of(
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", List.of(Map.of(
                                        "image", imageDataUrl,
                                        "min_pixels", 3072,
                                        "max_pixels", 8388608,
                                        "enable_rotate", false
                                ))
                        ))
                ),
                "parameters", Map.of(
                        "ocr_options", Map.of("task", properties.getTask())
                )
        );
    }

    private String parseDashScopeText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        JSONObject root = JSON.parseObject(responseBody);
        JSONObject output = root.getJSONObject("output");
        if (output == null) {
            return "";
        }
        JSONArray choices = output.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (message == null) {
            return "";
        }
        JSONArray content = message.getJSONArray("content");
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.getJSONObject(0).getString("text");
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

    private record PageImage(int pageNumber, String dataUrl) {
    }
}
