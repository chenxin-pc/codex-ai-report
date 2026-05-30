package com.example.aimilvusweb.service.ingest;

import com.alibaba.fastjson2.JSON;
import com.example.aimilvusweb.common.util.SemanticChunkUtils.ReportChunkSlice;
import com.example.aimilvusweb.config.ReportQualityProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 研报 chunk 入库过滤策略，负责判断切片是否应进入最终 chunk 与 Milvus 候选集合。
 * @Logic: 按 segmentType、sectionPath、最低 token、文本长度、汉字比例、噪声比例和财务表格豁免生成过滤原因与诊断。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Component
public class ReportChunkFilterPolicy {

    /** 章节路径关键词黑名单，命中后该切片会被判定为低价值内容并过滤。 */
    private static final Set<String> EXCLUDED_SECTION_KEYWORDS = Set.of(
            "免责声明", "免责条款", "法律声明", "分析师承诺", "评级说明", "投资评级说明", "风险披露",
            "分析师声明", "研究所联系方式", "联系方式", "券商简介", "机构介绍", "中邮证券研究所"
    );
    /** 语义段类型黑名单，命中后切片不会进入最终有效 chunk 集合。 */
    private static final Set<String> EXCLUDED_SEGMENT_TYPES = Set.of(
            "DISCLAIMER", "ANALYST_DECLARATION", "BROKER_PROFILE", "CONTACT_INFO", "LAYOUT_NOISE"
    );
    /** 财务表格主题关键词集合，用于低汉字比例豁免判断。 */
    private static final Set<String> FINANCIAL_TABLE_KEYWORDS = Set.of(
            "盈利预测", "财务指标", "财务报表", "主要财务比率", "利润表", "资产负债表", "现金流量表",
            "营业收入", "归母净利润", "每股收益", "EPS", "P/E", "P/B", "市盈率", "市净率"
    );

    /** 质量配置，提供最小切片 token 阈值。 */
    private final ReportQualityProperties reportQualityProperties;

    /**
     * @Description: 初始化 chunk 过滤策略。
     * @Logic: 保存质量配置，后续低语义过滤读取 minSliceTokenCount。
     * @Param: reportQualityProperties 研报质量配置。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public ReportChunkFilterPolicy(ReportQualityProperties reportQualityProperties) {
        this.reportQualityProperties = reportQualityProperties;
    }

    /**
     * @Description: 判断切片是否保留。
     * @Logic: 过滤原因为空表示保留，非空表示应只记录诊断且不写入最终 chunk/Milvus。
     * @Param: slice 切片候选。
     * @Return: true 表示保留。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public boolean shouldKeepSlice(ReportChunkSlice slice) {
        return resolveFilterReason(slice) == null;
    }

    /**
     * @Description: 解析切片过滤原因。
     * @Logic: 先使用 LLM segmentType 黑名单，再用 sectionPath 关键词兜底，最后执行低语义质量判断。
     * @Param: slice 切片候选。
     * @Return: 过滤原因；返回 null 表示切片应保留。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public String resolveFilterReason(ReportChunkSlice slice) {
        String segmentType = normalizedSegmentType(slice);
        if (EXCLUDED_SEGMENT_TYPES.contains(segmentType)) {
            return "EXCLUDED_SEGMENT_TYPE:" + segmentType;
        }
        String sectionPath = slice.sectionPath() == null ? "" : slice.sectionPath().trim();
        if (!sectionPath.isBlank()) {
            for (String keyword : EXCLUDED_SECTION_KEYWORDS) {
                if (sectionPath.contains(keyword)) {
                    return "EXCLUDED_SECTION:" + keyword;
                }
            }
        }
        return resolveLowSemanticReason(slice);
    }

    /**
     * @Description: 构建切片诊断 JSON。
     * @Logic: 记录段落范围、页码范围、segmentType、财务表格候选和过滤原因，供质量观测与导出脚本使用。
     * @Param: slice 切片候选；filterReason 过滤原因。
     * @Return: JSON 字符串诊断信息。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public String buildChunkDiagnostics(ReportChunkSlice slice, String filterReason) {
        Map<String, Object> diagnostics = new HashMap<>();
        diagnostics.put("startParagraphId", slice.startParagraphId());
        diagnostics.put("endParagraphId", slice.endParagraphId());
        diagnostics.put("startPageNumber", slice.startPageNumber());
        diagnostics.put("endPageNumber", slice.endPageNumber());
        diagnostics.put("segmentType", normalizedSegmentType(slice));
        diagnostics.put("financialTableCandidate", isFinancialTableCandidate(slice));
        diagnostics.put("filterReason", filterReason == null ? "" : filterReason);
        return JSON.toJSONString(diagnostics);
    }

    /**
     * @Description: 根据文本质量解析低语义过滤原因。
     * @Logic: 先判断 token 阈值，再处理财务表格豁免，随后按文本长度、汉字比例和噪声比例判断。
     * @Param: slice 切片候选。
     * @Return: 过滤原因；返回 null 表示通过低语义过滤。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private String resolveLowSemanticReason(ReportChunkSlice slice) {
        if (slice.tokenCount() < reportQualityProperties.getChunk().getMinSliceTokenCount()) {
            return "LOW_TOKEN_COUNT:" + slice.tokenCount();
        }
        if (isFinancialTableCandidate(slice)) {
            return null;
        }
        String text = slice.text() == null ? "" : slice.text();
        String normalized = text.replaceAll("\\s+", "");
        if (normalized.length() < 60) {
            return "SHORT_TEXT:" + normalized.length();
        }
        int han = 0;
        int digits = 0;
        int symbols = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                han++;
            } else if (Character.isDigit(ch)) {
                digits++;
            } else if (!Character.isLetter(ch)) {
                symbols++;
            }
        }
        double hanRatio = han / (double) normalized.length();
        double noiseRatio = (digits + symbols) / (double) normalized.length();
        if (hanRatio < 0.20D) {
            return "LOW_HAN_RATIO:" + hanRatio;
        }
        if (noiseRatio > 0.65D) {
            return "HIGH_NOISE_RATIO:" + noiseRatio;
        }
        return null;
    }

    /**
     * @Description: 标准化语义段类型。
     * @Logic: 空值统一为 OTHER，非空值去空白并转为大写，避免大小写影响过滤。
     * @Param: slice 切片候选。
     * @Return: 标准化 segmentType。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private String normalizedSegmentType(ReportChunkSlice slice) {
        String segmentType = slice.segmentType();
        if (segmentType == null || segmentType.isBlank()) {
            return "OTHER";
        }
        return segmentType.trim().toUpperCase();
    }

    /**
     * @Description: 判断切片是否为财务表格候选。
     * @Logic: FINANCIAL_TABLE/FINANCIAL_FORECAST 类型直接保留；否则使用 sectionPath 和正文关键词兜底识别。
     * @Param: slice 切片候选。
     * @Return: true 表示该切片满足财务表格豁免候选条件。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    private boolean isFinancialTableCandidate(ReportChunkSlice slice) {
        String segmentType = normalizedSegmentType(slice);
        if ("FINANCIAL_TABLE".equals(segmentType) || "FINANCIAL_FORECAST".equals(segmentType)) {
            return true;
        }
        String haystack = ((slice.sectionPath() == null ? "" : slice.sectionPath()) + "\n" + (slice.text() == null ? "" : slice.text())).toUpperCase();
        for (String keyword : FINANCIAL_TABLE_KEYWORDS) {
            if (haystack.contains(keyword.toUpperCase())) {
                return true;
            }
        }
        return false;
    }
}
