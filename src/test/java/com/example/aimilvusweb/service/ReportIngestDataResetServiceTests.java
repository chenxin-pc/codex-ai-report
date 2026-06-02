package com.example.aimilvusweb.service;

import com.example.aimilvusweb.dto.ReportIngestDataResetRespDTO;
import com.example.aimilvusweb.repository.ReportIngestDataResetMapper;
import com.example.aimilvusweb.service.retrieval.ReportHybridVectorStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: ReportIngestDataResetService 单元测试，验证导入域清空保护和删除范围。
 * @Logic: 使用 mock Mapper 断言确认短语、MySQL 删除顺序响应和 Milvus collection 删除触发。
 * @Param: 无。
 * @Return: 无（仅断言清空服务行为）。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
class ReportIngestDataResetServiceTests {

    /**
     * @Description: 验证确认短语正确时清空导入域数据。
     * @Logic: 每个导入域表删除一次，并在 MySQL 清理后触发 Milvus collection 删除。
     * @Param: 无。
     * @Return: 无（仅断言响应和调用）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Test
    void shouldResetIngestDomainAndDropCollectionWhenConfirmed() {
        // 创建清空 Mapper mock。
        ReportIngestDataResetMapper resetMapper = mock(ReportIngestDataResetMapper.class);
        // 创建 hybrid 向量存储 mock。
        ReportHybridVectorStore hybridVectorStore = mock(ReportHybridVectorStore.class);
        // 模拟代表性删除行数。
        when(resetMapper.deleteDocuments()).thenReturn(3);
        // 创建清空服务。
        ReportIngestDataResetService service = new ReportIngestDataResetService(resetMapper, hybridVectorStore);

        // 执行清空。
        ReportIngestDataResetRespDTO response = service.reset(ReportIngestDataResetService.CONFIRM_PHRASE);

        // 响应中包含 report_document 删除行数。
        Assertions.assertEquals(3, response.deletedRowsByTable().get("report_document"));
        // 响应中标记已触发 Milvus 删除。
        Assertions.assertTrue(response.milvusCollectionDropped());
        // 验证 vector metadata 任务在 chunk 前清理。
        verify(resetMapper).deleteVectorMetadataSyncJobs();
        // 验证报告主档最终清理。
        verify(resetMapper).deleteDocuments();
        // 验证 collection 删除只由显式清空动作触发。
        verify(hybridVectorStore).dropCollection();
    }

    /**
     * @Description: 验证确认短语错误时不执行任何删除。
     * @Logic: 短语不匹配直接抛错，并且不调用 MySQL 或 Milvus 删除。
     * @Param: 无。
     * @Return: 无（仅断言保护逻辑）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Test
    void shouldRejectResetWhenConfirmPhraseIsWrong() {
        // 创建清空 Mapper mock。
        ReportIngestDataResetMapper resetMapper = mock(ReportIngestDataResetMapper.class);
        // 创建 hybrid 向量存储 mock。
        ReportHybridVectorStore hybridVectorStore = mock(ReportHybridVectorStore.class);
        // 创建清空服务。
        ReportIngestDataResetService service = new ReportIngestDataResetService(resetMapper, hybridVectorStore);

        // 断言错误确认短语会抛异常。
        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class, () -> service.reset("wrong"));

        // 异常提示包含正确短语。
        Assertions.assertTrue(exception.getMessage().contains(ReportIngestDataResetService.CONFIRM_PHRASE));
        // MySQL 删除不会执行。
        verify(resetMapper, never()).deleteDocuments();
        // Milvus collection 删除不会执行。
        verify(hybridVectorStore, never()).dropCollection();
    }
}
