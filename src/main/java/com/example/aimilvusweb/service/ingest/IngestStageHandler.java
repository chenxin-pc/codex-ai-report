package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.entity.IngestJob;
import com.example.aimilvusweb.enums.IngestStageEnum;

/**
 * @Description: 单个入库阶段业务动作接口，封装 OCR、CHUNK、VECTOR 各自的具体执行逻辑。
 * @Logic: 阶段执行模板负责通用状态流，handler 只负责执行当前阶段并返回输出规模。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
public interface IngestStageHandler {

    /**
     * @Description: 返回 handler 支持的阶段。
     * @Logic: 用于阶段执行器按 OCR/CHUNK/VECTOR 路由到正确 handler。
     * @Param: 无。
     * @Return: 支持的入库阶段枚举。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    IngestStageEnum stage();

    /**
     * @Description: 执行当前阶段业务动作。
     * @Logic: handler 内部调用对应入库服务，异常直接抛给阶段模板统一记录失败和重试。
     * @Param: job 导入任务。
     * @Return: 阶段输出数量，用于阶段事件 outputSize。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    int execute(IngestJob job) throws Exception;
}
