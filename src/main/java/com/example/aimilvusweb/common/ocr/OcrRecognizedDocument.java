package com.example.aimilvusweb.common.ocr;

import java.util.List;

/**
 * @Description: OcrRecognizedDocument类，负责相关业务能力的组织与实现。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
         * @author: cx
         * @Date: 2026-05-17 10:24:01
         */
        public OcrPage(int pageNumber, String text) {
            this(pageNumber, text, "");
        }
    }
}
