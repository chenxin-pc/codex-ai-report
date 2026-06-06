package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.enums.IngestStageEnum;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 入库阶段执行模板，统一串联短事务 claim、阶段 handler 执行、成功收尾和失败收尾。
 * @Logic: 通过阶段定义定位 handler，外部动作在 claim 与 complete/fail 两个短事务之间执行，失败由统一 policy 记录。
 * @Param: 无。
 * @Return: 阶段执行服务对象，对外暴露单阶段推进能力。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Service
public class IngestStageExecutor {

    /** 阶段 handler 映射，用于按 OCR/CHUNK/VECTOR 路由具体业务动作。 */
    private final Map<IngestStageEnum, IngestStageHandler> handlerByStage;
    /** 阶段短事务服务，负责状态更新与事件落库。 */
    private final IngestStageTransactionService transactionService;

    /**
     * @Description: 初始化阶段执行模板。
     * @Logic: 将 Spring 注入的 handler 列表整理为 EnumMap，缺失 handler 时在执行阶段抛出明确异常。
     * @Param: handlers 阶段 handler 列表；transactionService 阶段短事务服务。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public IngestStageExecutor(List<IngestStageHandler> handlers,
                               IngestStageTransactionService transactionService) {
        // 使用 EnumMap 存储 handler，阶段枚举查找比字符串匹配更明确。
        EnumMap<IngestStageEnum, IngestStageHandler> mapping = new EnumMap<>(IngestStageEnum.class);
        // 遍历 Spring 注入的所有 handler，建立阶段到 handler 的路由表。
        for (IngestStageHandler handler : handlers) {
            // 同一阶段若出现多个 handler，后注册者覆盖前者，启动期测试可暴露配置问题。
            mapping.put(handler.stage(), handler);
        }
        // 保存不可变引用，后续 process 调用只读取路由表。
        this.handlerByStage = mapping;
        // 保存短事务服务，状态推进和事件落库都通过它完成。
        this.transactionService = transactionService;
    }

    /**
     * @Description: 执行单个任务的单个阶段。
     * @Logic: 先短事务标记 PROCESSING，再执行阶段 handler；handler 成功则完成成功收尾，失败则完成失败收尾。
     * @Param: definition 阶段定义；jobUid 任务唯一标识。
     * @Return: 无（仅推进任务阶段状态并写入阶段事件）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public void process(IngestStageDefinition definition, String jobUid) {
        // 在短事务中占用任务并标记 PROCESSING，避免多个调度线程重复处理同一阶段。
        IngestStageExecution execution = transactionService.claim(definition, jobUid);
        try {
            // 按阶段定义找到具体 handler，并在事务外执行 OCR/切片/向量等耗时动作。
            int outputSize = requireHandler(definition.stage()).execute(execution.getJob());
            // handler 成功后进入短事务成功收尾，写状态和阶段事件。
            transactionService.complete(definition, execution, outputSize);
        } catch (Exception ex) {
            // handler 抛错时交给短事务失败收尾，统一判断重试或最终失败。
            transactionService.fail(definition, execution, ex);
        }
    }

    /**
     * @Description: 获取阶段 handler。
     * @Logic: 从 EnumMap 读取 handler，缺失时抛出状态异常，避免静默跳过阶段。
     * @Param: stage 入库阶段枚举。
     * @Return: 阶段 handler。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private IngestStageHandler requireHandler(IngestStageEnum stage) {
        // 按阶段枚举从路由表获取 handler。
        IngestStageHandler handler = handlerByStage.get(stage);
        // handler 缺失代表 Spring Bean 配置不完整，必须明确失败。
        if (handler == null) {
            throw new IllegalStateException("Missing ingest stage handler: " + stage.code());
        }
        // 返回可执行当前阶段动作的 handler。
        return handler;
    }
}
