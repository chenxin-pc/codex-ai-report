package com.example.aimilvusweb.service;

import com.example.aimilvusweb.common.ocr.OcrClient;
import com.example.aimilvusweb.common.ocr.OcrRecognizedDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportOcrParseServiceTests {

    @Test
    void shouldParseConfiguredOcrPagesIntoNormalizedReportText() {
        OcrClient ocrClient = mock(OcrClient.class);
        ReportOcrParseService service = new ReportOcrParseService(ocrClient);
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "demo".getBytes());

        when(ocrClient.isConfigured()).thenReturn(true);
        when(ocrClient.recognize(file)).thenReturn(new OcrRecognizedDocument(
                "",
                List.of(new OcrRecognizedDocument.OcrPage(1, """
                        投资要点
                        我们认为行业需求
                        有望继续改善。
                        风险提示
                        原材料价格波动。
                        """))
        ));

        String text = service.parse(file);

        Assertions.assertTrue(text.contains("[Page 1]"));
        Assertions.assertTrue(text.contains("投资要点"));
        Assertions.assertTrue(text.contains("我们认为行业需求有望继续改善。"));
        Assertions.assertTrue(text.contains("风险提示"));
    }

    @Test
    void shouldExposePageDiagnosticsAndParagraphAtoms() {
        OcrClient ocrClient = mock(OcrClient.class);
        ReportOcrParseService service = new ReportOcrParseService(ocrClient);
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "demo".getBytes());

        when(ocrClient.isConfigured()).thenReturn(true);
        when(ocrClient.recognize(file)).thenReturn(new OcrRecognizedDocument(
                "",
                List.of(new OcrRecognizedDocument.OcrPage(2, """
                        投资要点

                        我们认为需求改善。

                        1234567890%%%%%%%%
                        """, "rotationChecked"))
        ));

        ReportOcrParseService.ReportOcrParseResult result = service.parseDetailed(file);

        Assertions.assertEquals(1, result.pages().size());
        Assertions.assertEquals(2, result.pages().get(0).pageNumber());
        Assertions.assertTrue(result.pages().get(0).diagnostics().contains("removedNoiseLineCount"));
        Assertions.assertEquals(1, result.atoms().size());
        Assertions.assertEquals(2, result.atoms().get(0).pageNumber());
        Assertions.assertEquals("投资要点", result.atoms().get(0).sectionPath());
    }

    @Test
    void shouldFailWhenOcrIsNotConfigured() {
        OcrClient ocrClient = mock(OcrClient.class);
        ReportOcrParseService service = new ReportOcrParseService(ocrClient);
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "demo".getBytes());

        when(ocrClient.isConfigured()).thenReturn(false);

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.parse(file)
        );

        Assertions.assertTrue(exception.getMessage().contains("OCR is required"));
    }

    @Test
    void shouldNormalizeOcrWrappedLinesAndSemanticBoundaries() {
        OcrClient ocrClient = mock(OcrClient.class);
        ReportOcrParseService service = new ReportOcrParseService(ocrClient);

        String text = service.normalizeOcrText("""
                投资要点
                我们认为行业景气
                度仍处于上行阶段。
                从供给端看，新增产能投放节奏低于预期。
                从需求端看，下游订单连续改善。
                """);

        Assertions.assertTrue(text.contains("我们认为行业景气度仍处于上行阶段。"));
        Assertions.assertTrue(text.contains("从供给端看"));
        Assertions.assertTrue(text.contains("从需求端看"));
    }
}
