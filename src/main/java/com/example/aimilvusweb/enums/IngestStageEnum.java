package com.example.aimilvusweb.enums;

import com.example.aimilvusweb.entity.IngestJob;

import java.util.Arrays;

/**
 * @Description: 异步导入阶段枚举，集中维护 OCR、CHUNK、VECTOR 阶段编码、状态字段和尝试次数字段访问逻辑。
 * @Logic: 每个枚举值负责读写自身阶段的 status/attempt，并声明后置阶段执行前必须满足的前置成功条件。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
public enum IngestStageEnum {

    /** OCR 阶段，负责创建报告主档并落库页级 OCR 与段落 atom。 */
    OCR("OCR") {
        @Override
        public String getStatus(IngestJob job) {
            return job.getOcrStatus();
        }

        @Override
        public void setStatus(IngestJob job, String status) {
            job.setOcrStatus(status);
        }

        @Override
        public Integer getAttemptCount(IngestJob job) {
            return job.getOcrAttemptCount();
        }

        @Override
        public void setAttemptCount(IngestJob job, Integer attemptCount) {
            job.setOcrAttemptCount(attemptCount);
        }

        @Override
        public boolean prerequisitesSatisfied(IngestJob job) {
            return true;
        }
    },

    /** CHUNK 阶段，负责读取段落 atom 并生成 PARENT/CHILD chunk。 */
    CHUNK("CHUNK") {
        @Override
        public String getStatus(IngestJob job) {
            return job.getChunkStatus();
        }

        @Override
        public void setStatus(IngestJob job, String status) {
            job.setChunkStatus(status);
        }

        @Override
        public Integer getAttemptCount(IngestJob job) {
            return job.getChunkAttemptCount();
        }

        @Override
        public void setAttemptCount(IngestJob job, Integer attemptCount) {
            job.setChunkAttemptCount(attemptCount);
        }

        @Override
        public boolean prerequisitesSatisfied(IngestJob job) {
            return IngestStageStatusEnum.SUCCEEDED.code().equals(job.getOcrStatus());
        }
    },

    /** VECTOR 阶段，负责将未入向量的 CHILD chunk 写入 Milvus 并回写状态。 */
    VECTOR("VECTOR") {
        @Override
        public String getStatus(IngestJob job) {
            return job.getVectorStatus();
        }

        @Override
        public void setStatus(IngestJob job, String status) {
            job.setVectorStatus(status);
        }

        @Override
        public Integer getAttemptCount(IngestJob job) {
            return job.getVectorAttemptCount();
        }

        @Override
        public void setAttemptCount(IngestJob job, Integer attemptCount) {
            job.setVectorAttemptCount(attemptCount);
        }

        @Override
        public boolean prerequisitesSatisfied(IngestJob job) {
            return IngestStageStatusEnum.SUCCEEDED.code().equals(job.getOcrStatus())
                    && IngestStageStatusEnum.SUCCEEDED.code().equals(job.getChunkStatus());
        }
    };

    /** 阶段持久化编码，与 ingest_job 和阶段事件中的 stage 字段一致。 */
    private final String code;

    IngestStageEnum(String code) {
        this.code = code;
    }

    /**
     * @Description: 返回阶段持久化编码。
     * @Logic: 直接返回枚举构造时绑定的 OCR/CHUNK/VECTOR 编码。
     * @Param: 无。
     * @Return: 阶段编码。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public String code() {
        return code;
    }

    /**
     * @Description: 读取当前阶段在任务上的状态字段。
     * @Logic: OCR/CHUNK/VECTOR 分别读取 ocrStatus、chunkStatus、vectorStatus。
     * @Param: job 导入任务。
     * @Return: 当前阶段状态码。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public abstract String getStatus(IngestJob job);

    /**
     * @Description: 写入当前阶段在任务上的状态字段。
     * @Logic: OCR/CHUNK/VECTOR 分别写入 ocrStatus、chunkStatus、vectorStatus。
     * @Param: job 导入任务；status 目标状态码。
     * @Return: 无（仅更新任务对象字段）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public abstract void setStatus(IngestJob job, String status);

    /**
     * @Description: 读取当前阶段尝试次数。
     * @Logic: OCR/CHUNK/VECTOR 分别读取 ocrAttemptCount、chunkAttemptCount、vectorAttemptCount。
     * @Param: job 导入任务。
     * @Return: 当前阶段尝试次数，可为空。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public abstract Integer getAttemptCount(IngestJob job);

    /**
     * @Description: 写入当前阶段尝试次数。
     * @Logic: OCR/CHUNK/VECTOR 分别写入 ocrAttemptCount、chunkAttemptCount、vectorAttemptCount。
     * @Param: job 导入任务；attemptCount 最新尝试次数。
     * @Return: 无（仅更新任务对象字段）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public abstract void setAttemptCount(IngestJob job, Integer attemptCount);

    /**
     * @Description: 判断当前阶段前置条件是否满足。
     * @Logic: OCR 无前置依赖；CHUNK 依赖 OCR 成功；VECTOR 依赖 OCR 与 CHUNK 均成功。
     * @Param: job 导入任务。
     * @Return: true 表示允许执行当前阶段。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public abstract boolean prerequisitesSatisfied(IngestJob job);

    /**
     * @Description: 将阶段编码解析为枚举。
     * @Logic: 忽略大小写匹配 OCR/CHUNK/VECTOR，未知编码抛出明确参数异常。
     * @Param: code 阶段编码。
     * @Return: 匹配的阶段枚举。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public static IngestStageEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Ingest stage is required");
        }
        return Arrays.stream(values())
                .filter(stage -> stage.code.equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported ingest stage: " + code));
    }
}
