package com.example.aimilvusweb.common.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public final class PdfUtils {

    private PdfUtils() {
    }

    public static String extractText(MultipartFile file) {
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("PDF contains no extractable text");
            }
            return text;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse PDF: " + e.getMessage(), e);
        }
    }
}
