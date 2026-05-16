package com.example.aimilvusweb.service;

public class LlmSemanticChunkException extends RuntimeException {

    public LlmSemanticChunkException(String message) {
        super(message);
    }

    public LlmSemanticChunkException(String message, Throwable cause) {
        super(message, cause);
    }
}
