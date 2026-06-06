package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.entity.IngestJob;
import com.example.aimilvusweb.enums.IngestStageEnum;
import com.example.aimilvusweb.enums.IngestStageStatusEnum;
import lombok.Getter;

/**
 * @Description: 入库阶段定义，绑定阶段枚举与运行时模型名，并代理状态和尝试次数字段操作。
 * @Logic: 阶段执行模板通过该对象读取阶段编码、模型名、前置依赖、状态写入和 attempt 累加逻辑，避免字符串分支散落。
 * @Param: 无。
 * @Return: 阶段定义对象，承载阶段元数据和任务状态字段访问规则。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Getter
public class IngestStageDefinition {

    /** 当前阶段枚举，决定状态字段和前置依赖。 */
    private final IngestStageEnum stage;
    /** 当前阶段使用的模型名，用于阶段事件记录和观测展示。 */
    private final String modelName;

    /**
     * @Description: 创建阶段定义。
     * @Logic: 校验阶段枚举非空，模型名允许为空但会在事件中按原值记录。
     * @Param: stage 当前阶段枚举；modelName 当前阶段模型名。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public IngestStageDefinition(IngestStageEnum stage, String modelName) {
        // 阶段枚举决定状态字段和前置依赖，缺失时无法安全调度。
        if (stage == null) {
            throw new IllegalArgumentException("Ingest stage is required");
        }
        // 保存阶段枚举，后续所有状态读写都通过枚举代理。
        this.stage = stage;
        // 保存模型名，阶段事件会记录该值用于链路观测。
        this.modelName = modelName;
    }

    /**
     * @Description: 返回阶段编码。
     * @Logic: 委托阶段枚举返回 OCR/CHUNK/VECTOR 编码。
     * @Param: 无。
     * @Return: 阶段编码。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public String code() {
        // 统一从阶段枚举读取持久化编码，避免调用方硬编码字符串。
        return stage.code();
    }

    /**
     * @Description: 返回阶段枚举。
     * @Logic: 提供与阶段执行模板一致的轻量访问方法，避免调用方依赖 Lombok 生成方法名。
     * @Param: 无。
     * @Return: 当前阶段枚举。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public IngestStageEnum stage() {
        // 返回阶段枚举本体，供执行器查找对应 handler。
        return stage;
    }

    /**
     * @Description: 校验当前阶段前置依赖。
     * @Logic: 依赖未满足时抛出异常，避免手工调用后置阶段绕过 OCR/CHUNK 成功约束。
     * @Param: job 导入任务。
     * @Return: 无（依赖不满足时抛出异常）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public void ensurePrerequisitesSatisfied(IngestJob job) {
        // 按阶段枚举内置规则检查前序阶段，CHUNK/VECTOR 不能越过前置成功状态。
        if (!stage.prerequisitesSatisfied(job)) {
            throw new IllegalStateException("Prerequisite stage is not satisfied for " + code());
        }
    }

    /**
     * @Description: 增加当前阶段尝试次数。
     * @Logic: 空 attempt 视为 0，累加后写回当前阶段对应字段。
     * @Param: job 导入任务。
     * @Return: 累加后的尝试次数。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public int increaseAttempt(IngestJob job) {
        // 读取当前阶段 attempt，空值按 0 处理后加 1。
        int attempt = safeInt(stage.getAttemptCount(job)) + 1;
        // 将新 attempt 写回当前阶段对应字段，例如 ocrAttemptCount。
        stage.setAttemptCount(job, attempt);
        // 返回本次尝试次数，供阶段事件和重试策略使用。
        return attempt;
    }

    /**
     * @Description: 标记当前阶段状态。
     * @Logic: 将状态码写入当前阶段对应的 ocr/chunk/vector status 字段。
     * @Param: job 导入任务；status 目标状态枚举。
     * @Return: 无（仅更新任务对象状态字段）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public void markStatus(IngestJob job, IngestStageStatusEnum status) {
        // 将枚举状态码写入当前阶段对应状态字段，例如 chunkStatus。
        stage.setStatus(job, status.code());
    }

    /**
     * @Description: 安全读取整数值。
     * @Logic: null 统一回退为 0，避免 attempt 字段空值影响累加。
     * @Param: value 可空整数。
     * @Return: 非空整数。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private int safeInt(Integer value) {
        // 数据库历史值可能为空，统一转成 0 让 attempt 累加可预期。
        return value == null ? 0 : value;
    }
}
