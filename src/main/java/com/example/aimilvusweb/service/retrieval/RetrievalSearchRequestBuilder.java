package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.config.MilvusHybridProperties;
import com.example.aimilvusweb.infra.vector.ReportHybridSearchRequest;
import com.example.aimilvusweb.service.taxonomy.ResearchQueryAnchorService.QueryAnchors;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * @Description: 推荐召回 hybrid 请求构建组件。
 * @Logic: 基于用户 query、initialTopK 和结构化锚点构建 Milvus 原生 filter，并保留旧测试兼容 filter。
 * @Param: 无。
 * @Return: 无（无状态请求构建组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class RetrievalSearchRequestBuilder {

    /** hybrid 过滤配置，用于控制哪些锚点下推为 hard filter。 */
    private final MilvusHybridProperties properties;

    /**
     * @Description: 使用默认 hybrid 过滤配置初始化请求构建器。
     * @Logic: 兼容旧单元测试手动构造；生产链路由 ReportRetrievalService 传入配置实例。
     * @Param: 无。
     * @Return: 无（仅初始化默认配置）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public RetrievalSearchRequestBuilder() {
        // 默认配置与 application.yml 保持一致。
        this(new MilvusHybridProperties());
    }

    /**
     * @Description: 使用指定 hybrid 过滤配置初始化请求构建器。
     * @Logic: 保存配置后在 buildMilvusFilter 中决定 hard filter 下推范围。
     * @Param: properties hybrid 检索配置。
     * @Return: 无（仅初始化配置引用）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public RetrievalSearchRequestBuilder(MilvusHybridProperties properties) {
        // 配置缺失时回退默认值，避免测试构造空指针。
        this.properties = properties == null ? new MilvusHybridProperties() : properties;
    }

    /**
     * @Description: 构建 hybrid 检索请求。
     * @Logic: 固定下推 chunkType=CHILD；ticker/company/author 等确定性锚点进入 hard filter；旧测试兼容 filter 保留主题字段。
     * @Param: query 用户投研问题；topK 初始召回数量；anchors query 结构化锚点。
     * @Return: hybrid 检索请求。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public ReportHybridSearchRequest build(String query, int topK, QueryAnchors anchors) {
        // 构建 Milvus 原生表达式，生产 hybrid search 使用该表达式。
        String milvusFilter = buildMilvusFilter(anchors);
        // 构建 Spring AI filter，仅用于旧单元测试适配器。
        Filter.Expression legacyFilter = buildLegacyMetadataFilter(anchors);
        // 返回统一 hybrid 请求。
        return new ReportHybridSearchRequest(query, topK, milvusFilter, legacyFilter);
    }

    /**
     * @Description: 将 query 锚点转换为 Milvus 原生 filter。
     * @Logic: CHILD 是固定强约束；唯一 ticker/company 和作者锚点下推；主题和行业默认保留给排序加权避免宽泛 query 误杀召回。
     * @Param: anchors query 结构化锚点。
     * @Return: Milvus filter 表达式。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String buildMilvusFilter(QueryAnchors anchors) {
        // 初始化 AND 条件列表。
        List<String> conditions = new ArrayList<>();
        // 推荐召回只消费 CHILD chunk，PARENT 通过 MySQL 回查聚合。
        conditions.add(equalsExpression("chunkType", "CHILD"));
        // 唯一 ticker 是高置信锚点，可按配置作为 hard filter。
        if (properties.isUniqueTickerHardFilterEnabled()) {
            addUniqueEqualsCondition(conditions, "ticker", anchors.tickers());
        }
        // 唯一 company 是高置信锚点，可按配置作为 hard filter。
        if (properties.isUniqueCompanyHardFilterEnabled()) {
            addUniqueEqualsCondition(conditions, "companyName", anchors.companyCodes());
        }
        // 作者锚点由明确“作者/分析师”前缀抽取，可按配置支持多值 OR。
        if (properties.isAuthorFilterEnabled()) {
            addAuthorCondition(conditions, anchors.authorNames());
        }
        // 主题和行业默认不作为 hard filter；配置打开后才下推，适合小范围回归验证。
        if (properties.isThemeIndustryHardFilterEnabled()) {
            addMultiValueOrCondition(conditions, "reportThemeCode", anchors.themeCodes());
            addMultiValueOrCondition(conditions, "themeCode", anchors.themeCodes());
            addMultiValueOrCondition(conditions, "industryCode", anchors.industryCodes());
        }
        // 使用 AND 拼接 hard filter。
        return String.join(" and ", conditions);
    }

    /**
     * @Description: 构建旧 Spring AI metadata filter。
     * @Logic: 兼容旧单测对 SearchRequest filter 的断言，生产 Milvus 原生实现不使用该表达式。
     * @Param: anchors query 结构化锚点。
     * @Return: Spring AI filter；无锚点时只保留 CHILD 过滤。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private Filter.Expression buildLegacyMetadataFilter(QueryAnchors anchors) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op childFilter = builder.eq("chunkType", "CHILD");
        FilterExpressionBuilder.Op reportFilter = firstEq(builder, "reportThemeCode", anchors.themeCodes());
        FilterExpressionBuilder.Op queryFilter = null;
        queryFilter = orFilter(builder, queryFilter, firstEq(builder, "themeCode", anchors.themeCodes()));
        queryFilter = orFilter(builder, queryFilter, firstEq(builder, "industryCode", anchors.industryCodes()));
        queryFilter = orFilter(builder, queryFilter, firstEq(builder, "ticker", anchors.tickers()));
        queryFilter = orFilter(builder, queryFilter, firstEq(builder, "companyName", anchors.companyCodes()));
        queryFilter = orFilter(builder, queryFilter, firstEq(builder, "normalizedAuthor", anchors.authorNames()));
        return andFilter(builder, childFilter, andFilter(builder, reportFilter, queryFilter)).build();
    }

    /**
     * @Description: 使用列表首个值构造等值过滤表达式。
     * @Logic: Milvus metadata 使用 primary 单值字段参与 scalar filter；无值时返回 null 让调用方跳过该条件。
     * @Param: builder filter 构建器；field metadata 字段名；values 候选值列表。
     * @Return: 等值过滤表达式；无值时返回 null。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private FilterExpressionBuilder.Op firstEq(FilterExpressionBuilder builder, String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return builder.eq(field, values.get(0));
    }

    /**
     * @Description: 使用 OR 合并两个 filter 表达式。
     * @Logic: 左侧为空时返回右侧，右侧为空时返回左侧，两侧都有值时使用 OR 连接 query 锚点条件。
     * @Param: builder filter 构建器；left 左表达式；right 右表达式。
     * @Return: 合并后的 filter 表达式。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private FilterExpressionBuilder.Op orFilter(FilterExpressionBuilder builder,
                                                FilterExpressionBuilder.Op left,
                                                FilterExpressionBuilder.Op right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return builder.or(left, right);
    }

    /**
     * @Description: 使用 AND 合并两个 filter 表达式。
     * @Logic: 父标签强约束与 query 锚点过滤组合时使用 AND，避免父标签被 query OR 条件绕过。
     * @Param: builder filter 构建器；left 左表达式；right 右表达式。
     * @Return: 合并后的 filter 表达式。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private FilterExpressionBuilder.Op andFilter(FilterExpressionBuilder builder,
                                                 FilterExpressionBuilder.Op left,
                                                 FilterExpressionBuilder.Op right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return builder.and(left, right);
    }

    /**
     * @Description: 添加唯一等值 hard filter。
     * @Logic: 仅当锚点唯一时下推，多个候选交给排序层处理以避免误杀。
     * @Param: conditions 当前 AND 条件；field Milvus 字段；values 候选值。
     * @Return: 无（仅追加条件）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private void addUniqueEqualsCondition(List<String> conditions, String field, List<String> values) {
        // 空值或多候选都不作为 hard filter。
        if (values == null || values.size() != 1) {
            return;
        }
        // 追加唯一锚点等值条件。
        conditions.add(equalsExpression(field, values.get(0)));
    }

    /**
     * @Description: 添加作者多值过滤条件。
     * @Logic: 作者在 Milvus 中使用 |author1|author2| 文本投影，多作者 query 用 OR 连接 like 条件。
     * @Param: conditions 当前 AND 条件；authorNames 规范化作者名列表。
     * @Return: 无（仅追加条件）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private void addAuthorCondition(List<String> conditions, List<String> authorNames) {
        // 无作者锚点时不添加作者条件。
        if (authorNames == null || authorNames.isEmpty()) {
            return;
        }
        // 多作者使用 OR 表达式。
        StringJoiner joiner = new StringJoiner(" or ", "(", ")");
        // 遍历作者锚点。
        for (String authorName : authorNames) {
            // 空作者名跳过。
            if (authorName == null || authorName.isBlank()) {
                continue;
            }
            // 使用 authorText like 命中完整规范化作者名。
            joiner.add("authorText like \"%" + escapeLiteral("|" + authorName + "|") + "%\"");
        }
        // joiner 为空时不追加条件。
        String expression = joiner.toString();
        if (!"()".equals(expression)) {
            conditions.add(expression);
        }
    }

    /**
     * @Description: 添加多值 OR 等值过滤条件。
     * @Logic: 主题或行业被配置为 hard filter 时，同字段多值用 OR 表达，避免只取首值漏召回。
     * @Param: conditions 当前 AND 条件；field Milvus 字段；values 候选值。
     * @Return: 无（仅追加条件）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private void addMultiValueOrCondition(List<String> conditions, String field, List<String> values) {
        // 没有候选值时不追加过滤。
        if (values == null || values.isEmpty()) {
            return;
        }
        // 多个候选值使用 OR 连接。
        StringJoiner joiner = new StringJoiner(" or ", "(", ")");
        // 遍历所有候选值。
        for (String value : values) {
            // 跳过空值。
            if (value == null || value.isBlank()) {
                continue;
            }
            // 追加等值表达式。
            joiner.add(equalsExpression(field, value));
        }
        // joiner 非空时追加到 AND 条件列表。
        String expression = joiner.toString();
        if (!"()".equals(expression)) {
            conditions.add(expression);
        }
    }

    /**
     * @Description: 构造等值表达式。
     * @Logic: 对字段值做字符串转义，避免引号破坏 Milvus filter。
     * @Param: field 字段名；value 字段值。
     * @Return: Milvus 等值表达式。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String equalsExpression(String field, String value) {
        // 拼接 Milvus 字符串等值表达式。
        return field + " == \"" + escapeLiteral(value) + "\"";
    }

    /**
     * @Description: 转义 Milvus 字符串字面量。
     * @Logic: 仅转义反斜杠和双引号，避免用户输入破坏 filter 表达式结构。
     * @Param: value 原始字段值。
     * @Return: 可安全拼入双引号的文本。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String escapeLiteral(String value) {
        // null 值按空字符串处理。
        if (value == null) {
            return "";
        }
        // 先转义反斜杠，再转义双引号。
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
