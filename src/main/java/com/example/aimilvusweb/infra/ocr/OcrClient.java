package com.example.aimilvusweb.infra.ocr;

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
/**
 * @Description: OcrClient类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class OcrClient {

    /** OCR 配置对象，提供 endpoint、鉴权、模型与渲染参数。 */
    private final OcrProperties properties;
    /** 用于调用外部 OCR 接口的 HTTP 客户端。 */
    private final RestClient restClient;

    /**
     * @Description: 初始化OcrClient依赖与运行所需组件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public OcrClient(OcrProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    /**
     * @Description: 判断是否满足Configured条件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public boolean isConfigured() {
        return properties.getEndpoint() != null && !properties.getEndpoint().isBlank();
    }

    /**
     * @Description: 执行recognize相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 判断是否满足DashScopeEndpoint条件。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private boolean isDashScopeEndpoint() {
        String endpoint = properties.getEndpoint() == null ? "" : properties.getEndpoint();
        return endpoint.contains("dashscope.aliyuncs.com");
    }

    /**
     * @Description: 执行recognizeByDashScope相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 渲染输出文本或页面内容。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 执行对象到目标格式的转换。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 向外部服务发送请求并处理响应。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 根据上下文解析并确定最终值。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String resolveDashScopeGenerationEndpoint() {
        String endpoint = properties.getEndpoint().trim();
        if (endpoint.endsWith("/services/aigc/multimodal-generation/generation")) {
            return endpoint;
        }
        return endpoint.replaceAll("/+$", "") + "/services/aigc/multimodal-generation/generation";
    }

    /**
     * @Description: 构建目标对象或请求数据。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private Map<String, Object> buildDashScopeRequest(String imageDataUrl) {
        return Map.of(
                "model", properties.getModel(),
                "input", Map.of(
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of("text", """
                                                请识别这页研报内容并只输出纯文本。
                                                要求：
                                                1. 不要输出 Markdown 代码块、LaTeX 命令或排版标签。
                                                2. 不要输出 ```、\\begin、\\end、\\section、\\subsection、\\textbf 等标记。
                                                3. 不要输出页眉、页脚、页码、水印、券商免责声明、投资评级说明、分析师声明、联系方式。
                                                4. 表格请转为可读的纯文本行，保留指标名称、年份、单位和数值。
                                                5. 不要总结、改写或补充内容，只做 OCR 识别和格式清洗。
                                                """),
                                        Map.of(
                                                "image", imageDataUrl,
                                                "min_pixels", 3072,
                                                "max_pixels", 8388608,
                                                "enable_rotate", false
                                        )
                                )
                        ))
                ),
                "parameters", Map.of(
                        "ocr_options", Map.of("task", properties.getTask())
                )
        );
    }

    /**
     * @Description: 解析输入内容并输出结构化结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 向外部服务发送请求并处理响应。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String requestOcr(MultipartFile file) {
        try {
            String filename = file.getOriginalFilename() == null ? "report.pdf" : file.getOriginalFilename();
            ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
                /**
                 * @Description: 返回Filename字段当前值。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
                 * @author: cx
                 * @Date: 2026-05-17 10:24:01
                 */
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

    /**
     * @Description: 解析输入内容并输出结构化结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 解析输入内容并输出结构化结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 解析输入内容并输出结构化结果。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
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

    /**
     * @Description: 执行firstText相关业务处理。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    private String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.getString(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * @Description: PDF 页渲染结果，包含页号与该页 JPEG Data URL。
     * @Logic: 作为 DashScope 页级 OCR 请求输入，避免在主流程中重复处理图片编码。
     * @Param: pageNumber 页码（从 1 开始）；dataUrl JPEG base64 data url。
     * @Return: 无（仅数据载体）。
     * @author: cx
     * @Date: 2026-05-21 23:20:00
     */
    private record PageImage(int pageNumber, String dataUrl) {
    }
}
