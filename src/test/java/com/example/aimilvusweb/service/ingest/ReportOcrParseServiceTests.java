package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.infra.ocr.OcrClient;
import com.example.aimilvusweb.infra.ocr.OcrRecognizedDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportOcrParseServiceTests类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
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

        Assertions.assertFalse(text.contains("[Page 1]"));
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

    @Test
    void shouldCleanMarkdownLatexMarkupAndTableSeparators() {
        OcrClient ocrClient = mock(OcrClient.class);
        ReportOcrParseService service = new ReportOcrParseService(ocrClient);

        ReportOcrParseService.NormalizeResult result = service.normalizeOcrTextWithDiagnostics("""
                ```latex
                \\begin{flushright}
                \\section*{铜牛信息(300895)}
                \\subsection*{投资要点}
                \\end{flushright}
                \\begin{itemize}
                \\item \\textbf{投资建议}
                维持“买入”评级。
                \\end{itemize}
                \\begin{tabular}{lcc}
                \\hline
                项目 & 2026E & 2027E \\\\
                营业收入 & 338 & 447 \\\\
                EPS & -0.06 & 0.04 \\\\
                \\end{tabular}
                ```
                """);

        Assertions.assertFalse(result.text().contains("```"));
        Assertions.assertFalse(result.text().contains("\\begin"));
        Assertions.assertFalse(result.text().contains("\\section"));
        Assertions.assertFalse(result.text().contains("\\textbf"));
        Assertions.assertTrue(result.text().contains("铜牛信息(300895)"));
        Assertions.assertTrue(result.text().contains("投资要点"));
        Assertions.assertTrue(result.text().contains("投资建议"));
        Assertions.assertTrue(result.text().contains("项目 | 2026E | 2027E"));
        Assertions.assertTrue(result.text().contains("营业收入 | 338 | 447"));
        Assertions.assertTrue(result.diagnostics().contains("markupCleaned"));
        Assertions.assertTrue(result.diagnostics().contains("latexSection"));
    }
}
