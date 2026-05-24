package com.example.aimilvusweb.repository;

import com.example.aimilvusweb.entity.ResearchDictionaryTerm;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @Description: 投研结构化词库 Mapper，统一读取主题、行业、公司和代码 ACTIVE 词条。
 * @Logic: SQL 保持在 XML 中，服务层加载后构建词库快照和内存匹配索引。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 结构化词库词条列表或版本信息。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Mapper
public interface ResearchTaxonomyMapper {

    /**
     * @Description: 查询当前 ACTIVE 词库的统一词条列表。
     * @Logic: 合并主题词、行业词、公司别名和股票代码，返回供快照服务构建匹配器。
     * @Param: 无。
     * @Return: ACTIVE 词库词条列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    List<ResearchDictionaryTerm> selectActiveDictionaryTerms();

    /**
     * @Description: 查询当前 ACTIVE 词库版本。
     * @Logic: 优先返回主题词库最大版本；无主题词库时返回默认 v1，保障标签任务可记录版本。
     * @Param: 无。
     * @Return: 当前词库版本。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    String selectActiveDictionaryVersion();
}
