package com.example.aimilvusweb.common.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
/**
 * @Description: 封装通义千问 ChatClient 调用入口，提供文本对话与结构化输出能力。
 * @author: cx
 * @Date: 2026-05-17 10:31:14
 */
public class QwenClient {

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

    /**
     * @Description: 初始化 ChatClient.Builder 提供器，用于按需构建模型调用客户端。
     * @author: cx
     * @Date: 2026-05-17 10:31:14
     */
    public QwenClient(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
        this.chatClientBuilderProvider = chatClientBuilderProvider;
    }

    /**
     * @Description: 发送单轮文本 Prompt 并返回模型生成内容；未配置客户端时返回 null。
     * @author: cx
     * @Date: 2026-05-17 10:31:14
     */
    public String chat(String prompt) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            return null;
        }
        return builder.build().prompt(prompt).call().content();
    }

    /**
     * @Description: 以 system/user 双提示词调用模型，并将结果映射为指定结构化类型。
     * @author: cx
     * @Date: 2026-05-17 10:31:14
     */
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
