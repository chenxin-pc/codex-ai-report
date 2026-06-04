package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.dto.ReportIngestDataResetRespDTO;
import com.example.aimilvusweb.repository.ReportIngestDataResetMapper;
import com.example.aimilvusweb.infra.vector.ReportHybridVectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @Description: 研报导入域数据清空服务，执行可重建历史数据的显式删除。
 * @Logic: 校验确认短语后按外键依赖顺序删除 MySQL 导入域表，最后删除 Milvus hybrid collection。
 * @Param: 无。
 * @Return: 清空结果响应。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
@Service
public class ReportIngestDataResetService {

    /** 清空确认短语，避免误调用管理接口导致历史研报数据丢失。 */
    public static final String CONFIRM_PHRASE = "DELETE_REPORT_INGEST_DATA";
    /** 导入域清空 Mapper，负责按依赖顺序删除 MySQL 表数据。 */
    private final ReportIngestDataResetMapper resetMapper;
    /** hybrid 向量存储，负责删除可重建 Milvus collection。 */
    private final ReportHybridVectorStore hybridVectorStore;

    /**
     * @Description: 初始化导入域清空服务。
     * @Logic: 保存 MySQL 清空 Mapper 和 hybrid 向量存储，用于一次显式清空动作。
     * @Param: resetMapper 清空 Mapper；hybridVectorStore hybrid 向量存储。
     * @Return: 无（仅初始化服务依赖）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public ReportIngestDataResetService(ReportIngestDataResetMapper resetMapper,
                                        ReportHybridVectorStore hybridVectorStore) {
        // 保存清空 Mapper，后续按表依赖顺序执行 DELETE。
        this.resetMapper = resetMapper;
        // 保存 hybrid 向量存储，仅显式清空时允许删除 collection。
        this.hybridVectorStore = hybridVectorStore;
    }

    /**
     * @Description: 显式清空研报导入域历史数据。
     * @Logic: 必须传入固定确认短语；MySQL 表删除成功后删除 Milvus collection，失败时由调用方看到异常。
     * @Param: confirmPhrase 管理调用方传入的确认短语。
     * @Return: 清空结果响应。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Transactional
    public ReportIngestDataResetRespDTO reset(String confirmPhrase) {
        // 先校验确认短语，任何不匹配输入都不触发删除。
        requireConfirmPhrase(confirmPhrase);
        // LinkedHashMap 保持删除顺序，便于响应中人工核对依赖清理路径。
        Map<String, Integer> deletedRowsByTable = new LinkedHashMap<>();
        // 先删除依赖 chunk/report 的异步任务，避免后续外键或孤儿任务残留。
        deletedRowsByTable.put("report_vector_metadata_sync_job", resetMapper.deleteVectorMetadataSyncJobs());
        // 删除 chunk 标签任务，保证 chunk 删除前没有任务引用。
        deletedRowsByTable.put("report_chunk_tag_job", resetMapper.deleteChunkTagJobs());
        // 删除报告级标签，保留 taxonomy 主数据本身。
        deletedRowsByTable.put("report_document_tag", resetMapper.deleteDocumentTags());
        // 删除 chunk 标签，保留标签词库本身。
        deletedRowsByTable.put("report_chunk_tag", resetMapper.deleteChunkTags());
        // 删除导入阶段事件，清理历史观测时间线。
        deletedRowsByTable.put("report_ingest_stage_event", resetMapper.deleteStageEvents());
        // 删除导入任务，后续重新导入会重新创建任务。
        deletedRowsByTable.put("ingest_job", resetMapper.deleteIngestJobs());
        // 删除导入失败记录，避免旧错误污染新一轮导入观测。
        deletedRowsByTable.put("report_ingest_failure", resetMapper.deleteIngestFailures());
        // 删除 chunk 质量诊断，诊断与 chunk 一起重建。
        deletedRowsByTable.put("report_chunk_diagnostic", resetMapper.deleteChunkDiagnostics());
        // 删除最终检索 chunk，Milvus collection 也会同步删除。
        deletedRowsByTable.put("report_chunk", resetMapper.deleteChunks());
        // 删除 OCR 后的 paragraph atom。
        deletedRowsByTable.put("report_paragraph_atom", resetMapper.deleteParagraphAtoms());
        // 删除 OCR 页级结果。
        deletedRowsByTable.put("report_ocr_page", resetMapper.deleteOcrPages());
        // 删除研报作者事实表。
        deletedRowsByTable.put("report_document_author", resetMapper.deleteDocumentAuthors());
        // 最后删除研报主档。
        deletedRowsByTable.put("report_document", resetMapper.deleteDocuments());
        // 删除 Milvus collection；该动作只出现在显式清空入口。
        hybridVectorStore.dropCollection();
        // 返回完整清空影响范围。
        return new ReportIngestDataResetRespDTO(deletedRowsByTable, true);
    }

    /**
     * @Description: 校验清空确认短语。
     * @Logic: 使用固定短语作为破坏式动作保护，防止误触发历史数据删除。
     * @Param: confirmPhrase 调用方传入短语。
     * @Return: 无；短语错误时抛出异常。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private void requireConfirmPhrase(String confirmPhrase) {
        // 短语完全一致才允许继续。
        if (!CONFIRM_PHRASE.equals(confirmPhrase)) {
            // 抛出明确错误，提示调用方需要传入的固定确认短语。
            throw new IllegalArgumentException("confirm must be " + CONFIRM_PHRASE);
        }
    }
}
