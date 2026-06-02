package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.ResearchQueryAnchorService;
import com.example.aimilvusweb.service.ResearchQueryAnchorService.QueryAnchors;

import java.util.List;

/**
 * @Description: 推荐召回 query 锚点抽取组件。
 * @Logic: 正常链路委托 ResearchQueryAnchorService 抽取主题、行业、公司、代码和章节锚点；服务缺失时返回空锚点以兼容旧测试。
 * @Param: 无。
 * @Return: 无（锚点抽取组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class RetrievalAnchorExtractor {

    /** query 锚点抽取服务，可为空以兼容局部构造和旧测试。 */
    private final ResearchQueryAnchorService researchQueryAnchorService;

    /**
     * @Description: 初始化召回锚点抽取组件。
     * @Logic: 保存可选锚点服务，抽取时根据是否注入决定正常抽取或空锚点兜底。
     * @Param: researchQueryAnchorService query 锚点服务；允许为 null。
     * @Return: 无（仅初始化组件依赖）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public RetrievalAnchorExtractor(ResearchQueryAnchorService researchQueryAnchorService) {
        this.researchQueryAnchorService = researchQueryAnchorService;
    }

    /**
     * @Description: 从用户 query 抽取结构化检索锚点。
     * @Logic: 锚点服务未注入时返回空主题、行业、公司、代码、章节意图和命中词，保持纯向量召回兼容。
     * @Param: query 用户投研问题。
     * @Return: query 结构化锚点。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public QueryAnchors extract(String query) {
        if (researchQueryAnchorService == null) {
            return new QueryAnchors(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        return researchQueryAnchorService.extract(query);
    }
}
