package com.example.aimilvusweb.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Description: 研报质量参数配置，聚合切片策略与检索策略相关阈值。
 * @Logic: 由 Spring ConfigurationProperties 绑定 app.report-quality 前缀下的配置，供切片、召回和投研护栏链路读取。
 * @author: cx
 * @Date: 2026-05-17 10:53:07
 */
@Component
@Getter
@ConfigurationProperties(prefix = "app.report-quality")
public class ReportQualityProperties {

    /** 切片策略配置，控制父子切片大小、重叠和LLM分批参数。 */
    private final Chunk chunk = new Chunk();
    /** 检索策略配置，控制初始召回、最终TopK和父上下文长度。 */
    private final Retrieval retrieval = new Retrieval();
    /** 投研输入和输出护栏配置，控制词典、阈值和LLM兜底策略。 */
    private final QueryGuardrail queryGuardrail = new QueryGuardrail();

    /**
     * @Description: 切片参数配置，控制父子块大小、重叠范围与最小切片阈值。
     * @Logic: 入库链路读取这些参数生成 PARENT/CHILD chunk，并限制LLM语义切片的批量输入规模。
     * @author: cx
     * @Date: 2026-05-17 10:53:07
     */
    @Getter
    @Setter
    public static class Chunk {
        /** 子切片目标 token 数，用于控制 CHILD chunk 的理想长度。 */
        private int childTargetTokens = 700;
        /** 子切片最大 token 数，超过后需要拆分或重新组织。 */
        private int childMaxTokens = 1100;
        /** 父切片目标 token 数，用于控制 PARENT chunk 的理想上下文长度。 */
        private int parentTargetTokens = 3000;
        /** 父切片最大 token 数，超过后需要拆分或重新组织。 */
        private int parentMaxTokens = 5200;
        /** 相邻切片重叠 token 数，用于降低语义边界截断风险。 */
        private int overlapTokens = 120;
        /** 最小切片 token 数，低于该值的切片会被过滤或合并。 */
        private int minSliceTokenCount = 30;
        /** LLM切片规划单批最大段落数。 */
        private int llmMaxParagraphsPerBatch = 60;
        /** LLM切片规划单批最大 token 数。 */
        private int llmMaxTokensPerBatch = 8000;
        /** LLM分批规划时前后批次重叠段落数。 */
        private int llmOverlapParagraphs = 10;
        /** LLM分批规划时重叠内容最大 token 数。 */
        private int llmOverlapMaxTokens = 1200;
    }

    /**
     * @Description: 检索参数配置，控制召回数量、过滤阈值与上下文长度限制。
     * @Logic: 召回链路先按 initialTopK 拉取候选，再按过滤、重排和 finalTopK 输出展示证据。
     * @author: cx
     * @Date: 2026-05-17 10:53:07
     */
    @Getter
    @Setter
    public static class Retrieval {
        /** 初始向量召回数量，作为过滤和重排前的候选池大小。 */
        private int initialTopK = 20;
        /** 最终返回给推荐链路和前端展示的Top数量。 */
        private int finalTopK = 5;
        /** 最小相似度分数阈值，低于该值的候选会被过滤。 */
        private double minSimilarityScore = 0.0D;
        /** 是否启用重排逻辑。 */
        private boolean rerankEnabled = false;
        /** 父切片上下文最大 token 数，用于限制注入模型的上下文长度。 */
        private int maxParentContextTokens = 4500;
    }

    /**
     * @Description: 投研 query 护栏配置，控制本地词典路径、规则阈值和 LLM 兜底分类策略。
     * @Logic: 输入判定、证据质量降级和输出保护共同读取该配置。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    @Getter
    @Setter
    public static class QueryGuardrail {
        /** 是否启用输入可分析性和输出降级护栏。 */
        private boolean enabled = true;
        /** 本地规则词典目录，支持 classpath 路径。 */
        private String dictionaryPath = "query-guardrail/";
        /** 规则判定达到该置信度时不再调用 LLM 兜底。 */
        private double ruleHighConfidenceThreshold = 0.8D;
        /** 低于该置信度时允许进入 LLM 兜底分类。 */
        private double ruleLowConfidenceThreshold = 0.6D;
        /** 是否启用 LLM 兜底分类。 */
        private boolean llmFallbackEnabled = true;
        /** LLM 分类达到该置信度才允许进入召回。 */
        private double llmConfidenceThreshold = 0.7D;
        /** query 与证据重合分数低于该值时标记低相关。 */
        private int minQueryOverlapScore = 1;
    }
}
