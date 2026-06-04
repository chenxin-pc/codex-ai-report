package com.example.aimilvusweb.service.ingest;

import org.springframework.ai.document.Document;
import com.example.aimilvusweb.infra.vector.ReportHybridVectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Description: 入库向量批量写入器，负责按固定批次将 Document 写入 hybrid 向量存储。
 * @Logic: 将大列表按批量大小切分后逐批调用 ReportHybridVectorStore.write，调用方在全部写入成功后再回写 vectorStored。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 详见返回类型；void 时为无（仅副作用）。
 * @author: cx
 * @Date: 2026-05-30 16:00:00
 */
@Component
public class ReportVectorBatchWriter {

    /** 单批 embedding 写入数量，沿用原入库链路的请求体控制策略。 */
    private static final int EMBEDDING_BATCH_SIZE = 10;

    /**
     * @Description: 分批写入 hybrid 向量文档。
     * @Logic: 按 EMBEDDING_BATCH_SIZE 切分文档列表；任一批失败会抛出异常，调用方不会回写 vectorStored。
     * @Param: vectorStore hybrid 向量存储；documents 待写入文档列表。
     * @Return: 无（仅向 hybrid 向量存储写入数据）。
     * @author: cx
     * @Date: 2026-05-30 16:00:00
     */
    public void write(ReportHybridVectorStore vectorStore, List<Document> documents) {
        for (int start = 0; start < documents.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(documents.size(), start + EMBEDDING_BATCH_SIZE);
            vectorStore.write(documents.subList(start, end));
        }
    }
}
