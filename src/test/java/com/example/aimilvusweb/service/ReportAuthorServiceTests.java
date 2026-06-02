package com.example.aimilvusweb.service;

import com.example.aimilvusweb.entity.ReportDocumentAuthor;
import com.example.aimilvusweb.repository.ReportDocumentAuthorMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportAuthorService 单元测试，验证作者解析、规范化、MySQL 保存和 metadata 投影。
 * @Logic: 使用 mock Mapper 捕获写入实体，并模拟查询结果校验 Milvus author metadata 表达。
 * @Param: 无。
 * @Return: 无（仅断言服务行为）。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
class ReportAuthorServiceTests {

    /**
     * @Description: 验证多作者输入保存为规范化作者实体。
     * @Logic: 重复作者只保存一次，作者顺序按首次出现保留。
     * @Param: 无。
     * @Return: 无（仅断言保存结果）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Test
    void shouldSaveNormalizedDistinctAuthors() {
        // 创建作者 Mapper mock。
        ReportDocumentAuthorMapper authorMapper = mock(ReportDocumentAuthorMapper.class);
        // 创建作者服务。
        ReportAuthorService service = new ReportAuthorService(authorMapper);
        // 模拟每次 insert 返回一行受影响。
        when(authorMapper.insert(any(ReportDocumentAuthor.class))).thenReturn(1);

        // 保存包含重复值和不同分隔符的作者文本。
        int savedCount = service.saveImportAuthors(1L, "张 三、李四;张三");

        // 捕获写入实体。
        ArgumentCaptor<ReportDocumentAuthor> captor = ArgumentCaptor.forClass(ReportDocumentAuthor.class);
        // 验证先删除旧作者。
        verify(authorMapper).deleteByReportId(1L);
        // 验证只写入两个去重作者。
        verify(authorMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        // 读取捕获实体。
        List<ReportDocumentAuthor> authors = captor.getAllValues();
        // 返回值等于写入数量。
        Assertions.assertEquals(2, savedCount);
        // 第一个作者保留展示文本。
        Assertions.assertEquals("张 三", authors.get(0).getAuthorName());
        // 第一个作者写入规范化名称。
        Assertions.assertEquals("张三", authors.get(0).getNormalizedAuthorName());
        // 第二个作者保留顺序。
        Assertions.assertEquals("李四", authors.get(1).getAuthorName());
        // 第二个作者顺序为 1。
        Assertions.assertEquals(1, authors.get(1).getAuthorOrder());
    }

    /**
     * @Description: 验证作者 metadata 投影。
     * @Logic: 从 MySQL 作者事实表读取后生成 primary author、作者列表、规范化作者列表和 |author| 文本。
     * @Param: 无。
     * @Return: 无（仅断言 metadata 表达）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Test
    void shouldProjectAuthorMetadataForMilvus() {
        // 创建作者 Mapper mock。
        ReportDocumentAuthorMapper authorMapper = mock(ReportDocumentAuthorMapper.class);
        // 创建两个作者实体。
        ReportDocumentAuthor first = author(1L, "张三", "张三", 0);
        ReportDocumentAuthor second = author(1L, "李四", "李四", 1);
        // 模拟按报告查询作者。
        when(authorMapper.selectByReportId(1L)).thenReturn(List.of(first, second));
        // 创建作者服务。
        ReportAuthorService service = new ReportAuthorService(authorMapper);

        // 查询作者 metadata。
        ReportAuthorService.AuthorMetadata metadata = service.metadataForReport(1L);

        // 首个作者作为展示主作者。
        Assertions.assertEquals("张三", metadata.primaryAuthor());
        // 作者列表保留完整展示顺序。
        Assertions.assertEquals(List.of("张三", "李四"), metadata.authors());
        // 规范化作者列表用于检索过滤。
        Assertions.assertEquals(List.of("张三", "李四"), metadata.normalizedAuthors());
        // authorText 使用 | 包裹，避免短文本误匹配。
        Assertions.assertEquals("|张三|李四|", metadata.authorText());
    }

    /**
     * @Description: 构造作者实体。
     * @Logic: 测试只关心报告 ID、展示作者、规范化作者和顺序字段。
     * @Param: reportId 报告 ID；authorName 展示作者；normalizedAuthorName 规范化作者；order 作者顺序。
     * @Return: 作者实体。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private ReportDocumentAuthor author(Long reportId, String authorName, String normalizedAuthorName, int order) {
        // 创建作者实体。
        ReportDocumentAuthor author = new ReportDocumentAuthor();
        // 写入报告 ID。
        author.setReportId(reportId);
        // 写入展示作者。
        author.setAuthorName(authorName);
        // 写入规范化作者。
        author.setNormalizedAuthorName(normalizedAuthorName);
        // 写入作者顺序。
        author.setAuthorOrder(order);
        // 返回测试实体。
        return author;
    }
}
