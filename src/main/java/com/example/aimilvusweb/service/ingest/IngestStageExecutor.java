package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.enums.IngestStageEnum;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 入库阶段执行模板，统一串联短事务 claim、阶段 handler 执行、成功收尾和失败收尾。
 * @Logic: 通过阶段定义定位 handler，外部动作在 claim 与 complete/fail 两个短事务之间执行，失败由统一 policy 记录。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
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
        EnumMap<IngestStageEnum, IngestStageHandler> mapping = new EnumMap<>(IngestStageEnum.class);
        for (IngestStageHandler handler : handlers) {
            mapping.put(handler.stage(), handler);
        }
        this.handlerByStage = mapping;
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
        IngestStageExecution execution = transactionService.claim(definition, jobUid);
        try {
            int outputSize = requireHandler(definition.stage()).execute(execution.getJob());
            transactionService.complete(definition, execution, outputSize);
        } catch (Exception ex) {
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
        IngestStageHandler handler = handlerByStage.get(stage);
        if (handler == null) {
            throw new IllegalStateException("Missing ingest stage handler: " + stage.code());
        }
        return handler;
    }
}
