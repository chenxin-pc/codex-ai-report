package com.example.aimilvusweb.common.ocr;

import java.util.List;

/**
 * @Description: OcrRecognizedDocument类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public record OcrRecognizedDocument(
        String fullText,
        List<OcrPage> pages
) {

    public OcrRecognizedDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public record OcrPage(
            int pageNumber,
            String text,
            String diagnostics
    ) {
        /**
         * @Description: 初始化OcrPage依赖与运行所需组件。
         * @author: cx
         * @Date: 2026-05-17 10:24:01
         */
        public OcrPage(int pageNumber, String text) {
            this(pageNumber, text, "");
        }
    }
}
