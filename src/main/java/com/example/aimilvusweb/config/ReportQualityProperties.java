package com.example.aimilvusweb.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Description: 研报质量参数配置，聚合切片策略与检索策略相关阈值。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-17 10:53:07
 */
@Component
@Getter
@ConfigurationProperties(prefix = "app.report-quality")
public class ReportQualityProperties {

    private final Chunk chunk = new Chunk();
    private final Retrieval retrieval = new Retrieval();

    /**
     * @Description: 切片参数配置，控制父子块大小、重叠范围与最小切片阈值。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:53:07
     */
    @Getter
    @Setter
    public static class Chunk {
        private int childTargetTokens = 700;
        private int childMaxTokens = 1100;
        private int parentTargetTokens = 3000;
        private int parentMaxTokens = 5200;
        private int overlapTokens = 120;
        private int minSliceTokenCount = 30;
        private int llmMaxParagraphsPerBatch = 60;
        private int llmMaxTokensPerBatch = 8000;
        private int llmOverlapParagraphs = 10;
        private int llmOverlapMaxTokens = 1200;
    }

    /**
     * @Description: 检索参数配置，控制召回数量、过滤阈值与上下文长度限制。
 * @Logic: 按方法或类型既定职责执行业务处理并保证结果可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
     * @author: cx
     * @Date: 2026-05-17 10:53:07
     */
    @Getter
    @Setter
    public static class Retrieval {
        private int initialTopK = 20;
        private int finalTopK = 5;
        private double minSimilarityScore = 0.0D;
        private boolean rerankEnabled = false;
        private int maxParentContextTokens = 4500;
    }
}
