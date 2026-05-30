package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.ResearchQueryAnchorService.QueryAnchors;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;

/**
 * @Description: 推荐召回 SearchRequest 构建组件。
 * @Logic: 基于用户 query、initialTopK 和结构化锚点构建 Milvus ANN 请求，并在有锚点时附加 metadata scalar filter。
 * @Param: 无。
 * @Return: 无（无状态请求构建组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class RetrievalSearchRequestBuilder {

    /**
     * @Description: 构建 Spring AI SearchRequest。
     * @Logic: 先设置 query 和 topK，再将主题、行业和股票代码锚点转换为 metadata filter；无锚点时保持纯向量召回。
     * @Param: query 用户投研问题；topK 初始召回数量；anchors query 结构化锚点。
     * @Return: 向量库检索请求。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public SearchRequest build(String query, int topK, QueryAnchors anchors) {
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);
        FilterExpressionBuilder.Op filter = buildMetadataFilter(anchors);
        if (filter != null) {
            builder.filterExpression(filter.build());
        }
        return builder.build();
    }

    /**
     * @Description: 将 query 锚点转换为 metadata filter。
     * @Logic: 报告级主题父标签作为强约束，query 主题、行业和代码锚点使用 OR 组合后再与父标签 AND 合并。
     * @Param: anchors query 结构化锚点。
     * @Return: filter 表达式；无过滤锚点时返回 null。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private FilterExpressionBuilder.Op buildMetadataFilter(QueryAnchors anchors) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op reportFilter = firstEq(builder, "reportThemeCode", anchors.themeCodes());
        FilterExpressionBuilder.Op queryFilter = null;
        queryFilter = orFilter(builder, queryFilter, firstEq(builder, "themeCode", anchors.themeCodes()));
        queryFilter = orFilter(builder, queryFilter, firstEq(builder, "industryCode", anchors.industryCodes()));
        queryFilter = orFilter(builder, queryFilter, firstEq(builder, "ticker", anchors.tickers()));
        return andFilter(builder, reportFilter, queryFilter);
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
}
