package com.example.aimilvusweb.infra.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
/**
 * @Description: 封装通义千问 ChatClient 调用入口，提供文本对话与结构化输出能力。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:31:14
 */
public class QwenClient {

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

    /**
     * @Description: 初始化 ChatClient.Builder 提供器，用于按需构建模型调用客户端。
     * @Logic: 将外部注入的Builder提供器保存到成员变量，供文本、结构化和流式调用复用。
     * @Param: chatClientBuilderProvider ChatClient.Builder按需提供器。
     * @Return: 无（仅初始化对象状态）。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    public QwenClient(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
        this.chatClientBuilderProvider = chatClientBuilderProvider;
    }

    /**
     * @Description: 发送单轮文本 Prompt 并返回模型生成内容；未配置客户端时返回 null。
     * @Logic: 先从提供器获取Builder，缺失时直接返回null；存在时构建客户端并发起单轮文本调用。
     * @Param: prompt 单轮文本提示词。
     * @Return: 模型生成的文本内容；未配置模型客户端时返回null。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
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
     * @Logic: 获取Builder后按system与user提示词调用模型，并使用Spring AI实体映射输出；未配置时返回null。
     * @Param: systemPrompt 系统提示词；userPrompt 用户提示词；outputClass 目标结构化类型。
     * @Return: 映射后的结构化对象；未配置模型客户端时返回null。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
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

    /**
     * @Description: 以 system/user 双提示词调用模型，并返回文本增量流；未配置客户端时返回空流。
     * @Logic: 获取Builder后使用stream模式返回文本增量；若模型客户端未配置则返回空Flux作为降级。
     * @Param: systemPrompt 系统提示词；userPrompt 用户提示词。
     * @Return: 文本增量流；未配置模型客户端时返回Flux.empty()。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 14:48:00
     */
    public Flux<String> chatStream(String systemPrompt, String userPrompt) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            return Flux.empty();
        }
        return builder.build()
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content();
    }
}
