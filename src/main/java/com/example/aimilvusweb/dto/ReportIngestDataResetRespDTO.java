package com.example.aimilvusweb.dto;

import java.util.Map;

/**
 * @Description: 研报导入域清空响应 DTO，返回 MySQL 各表删除行数和 Milvus collection 删除结果。
 * @Logic: 管理入口执行破坏式清空后用该对象展示影响范围，便于人工复核清理是否完整。
 * @Param: deletedRowsByTable 各导入域表删除行数；milvusCollectionDropped 是否已触发 Milvus collection 删除。
 * @Return: 无（record 仅承载响应数据）。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
public record ReportIngestDataResetRespDTO(
        /** 按表名记录 MySQL 删除行数，key 与物理表名一致。 */
        Map<String, Integer> deletedRowsByTable,
        /** 是否已显式触发 hybrid Milvus collection 删除。 */
        boolean milvusCollectionDropped
) {
}
