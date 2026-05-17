package com.example.aimilvusweb.service;

/**
 * @Description: LlmSemanticChunkException类，负责相关业务能力的组织与实现。
 * @author: cx
 * @Date: 2026-05-17 10:24:01
 */
public class LlmSemanticChunkException extends RuntimeException {

    /**
     * @Description: 初始化LlmSemanticChunkException依赖与运行所需组件。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public LlmSemanticChunkException(String message) {
        super(message);
    }

    /**
     * @Description: 初始化LlmSemanticChunkException依赖与运行所需组件。
     * @author: cx
     * @Date: 2026-05-17 10:24:01
     */
    public LlmSemanticChunkException(String message, Throwable cause) {
        super(message, cause);
    }
}
