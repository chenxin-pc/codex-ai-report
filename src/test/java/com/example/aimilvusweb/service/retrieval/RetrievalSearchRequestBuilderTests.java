package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.config.MilvusHybridProperties;
import com.example.aimilvusweb.infra.vector.ReportHybridSearchRequest;
import com.example.aimilvusweb.service.taxonomy.ResearchQueryAnchorService.QueryAnchors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * @Description: RetrievalSearchRequestBuilder 单元测试，验证 Milvus hard filter 和 legacy filter 构造。
 * @Logic: 直接构造 query anchors，断言 ticker、company、author、多值 OR 与主题行业配置开关。
 * @Param: 无。
 * @Return: 无（仅断言请求表达式）。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
class RetrievalSearchRequestBuilderTests {

    /**
     * @Description: 验证高置信锚点进入 Milvus hard filter。
     * @Logic: CHILD 固定过滤，唯一 ticker、唯一 company 和多作者 OR 条件都会进入 filter。
     * @Param: 无。
     * @Return: 无（仅断言 filter 文本）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Test
    void shouldBuildMilvusHardFilterForTickerCompanyAndAuthors() {
        // 创建默认请求构建器。
        RetrievalSearchRequestBuilder builder = new RetrievalSearchRequestBuilder();
        // 构造包含唯一代码、唯一公司和多作者的锚点。
        QueryAnchors anchors = new QueryAnchors(
                List.of("STORAGE"),
                List.of("POWER"),
                List.of("宁德时代"),
                List.of("300750.SZ"),
                List.of("张三", "李四"),
                List.of(),
                List.of("储能")
        );

        // 构建 hybrid 请求。
        ReportHybridSearchRequest request = builder.build("作者张三和李四怎么看宁德时代", 20, anchors);

        // CHILD 强约束始终存在。
        Assertions.assertTrue(request.milvusFilter().contains("chunkType == \"CHILD\""));
        // 唯一股票代码进入 hard filter。
        Assertions.assertTrue(request.milvusFilter().contains("ticker == \"300750.SZ\""));
        // 唯一公司名进入 hard filter。
        Assertions.assertTrue(request.milvusFilter().contains("companyName == \"宁德时代\""));
        // 多作者使用 OR 连接 authorText like。
        Assertions.assertTrue(request.milvusFilter().contains("authorText like \"%|张三|%\" or authorText like \"%|李四|%\""));
        // 默认策略不把主题作为 hard filter。
        Assertions.assertFalse(request.milvusFilter().contains("reportThemeCode"));
        // legacy filter 保留兼容表达式。
        Assertions.assertNotNull(request.legacyFilterExpression());
    }

    /**
     * @Description: 验证主题行业 hard filter 开关。
     * @Logic: 配置打开后，主题和行业以多值 OR 形式下推给 Milvus。
     * @Param: 无。
     * @Return: 无（仅断言 filter 文本）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Test
    void shouldBuildThemeAndIndustryOrFilterWhenConfigured() {
        // 创建 hybrid 配置。
        MilvusHybridProperties properties = new MilvusHybridProperties();
        // 打开主题行业 hard filter。
        properties.setThemeIndustryHardFilterEnabled(true);
        // 创建请求构建器。
        RetrievalSearchRequestBuilder builder = new RetrievalSearchRequestBuilder(properties);
        // 构造多主题和行业锚点。
        QueryAnchors anchors = new QueryAnchors(
                List.of("STORAGE", "AI"),
                List.of("POWER"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("储能")
        );

        // 构建 hybrid 请求。
        ReportHybridSearchRequest request = builder.build("储能和 AI", 20, anchors);

        // 报告级主题下推为 OR 条件。
        Assertions.assertTrue(request.milvusFilter().contains("(reportThemeCode == \"STORAGE\" or reportThemeCode == \"AI\")"));
        // chunk 级主题下推为 OR 条件。
        Assertions.assertTrue(request.milvusFilter().contains("(themeCode == \"STORAGE\" or themeCode == \"AI\")"));
        // 行业下推为 OR 条件。
        Assertions.assertTrue(request.milvusFilter().contains("(industryCode == \"POWER\")"));
    }
}
