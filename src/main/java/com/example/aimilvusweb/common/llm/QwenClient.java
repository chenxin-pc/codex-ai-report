package com.example.aimilvusweb.common.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class QwenClient {

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

    public QwenClient(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
        this.chatClientBuilderProvider = chatClientBuilderProvider;
    }

    public String chat(String prompt) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            return null;
        }
        return builder.build().prompt(prompt).call().content();
    }

    public <T> T chatForEntity(String systemPrompt, String userPrompt, Class<T> outputClass) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            return null;
        }
        return builder.build()
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .entity(outputClass);
    }
}
