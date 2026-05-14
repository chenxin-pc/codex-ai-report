package com.example.aimilvusweb.common.ocr;

import java.util.List;

public record OcrRecognizedDocument(
        String fullText,
        List<OcrPage> pages
) {

    public OcrRecognizedDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public record OcrPage(
            int pageNumber,
            String text
    ) {
    }
}
