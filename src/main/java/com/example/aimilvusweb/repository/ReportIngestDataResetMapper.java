package com.example.aimilvusweb.repository;

import org.apache.ibatis.annotations.Mapper;

/**
 * @Description: 研报导入域清空 Mapper，按外键依赖顺序删除可重建导入数据。
 * @Logic: 只清理研报导入、切片、标签任务和向量同步数据，不触碰 taxonomy、词库、Prompt 或系统配置。
 * @Param: 无。
 * @Return: 各删除方法返回受影响行数。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
@Mapper
public interface ReportIngestDataResetMapper {

    /**
     * @Description: 删除向量 metadata 同步任务。
     * @Logic: 任务依赖 chunkUid 和 reportId，必须先于 chunk 清理。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteVectorMetadataSyncJobs();

    /**
     * @Description: 删除 chunk 标签抽取任务。
     * @Logic: 任务依赖 chunkUid 和 reportId，必须先于 chunk 清理。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteChunkTagJobs();

    /**
     * @Description: 删除报告级标签。
     * @Logic: 报告级标签属于导入结果，可随历史研报一起重建。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteDocumentTags();

    /**
     * @Description: 删除 chunk 标签。
     * @Logic: chunk 标签依赖 report/chunk，可随切片一起重建。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteChunkTags();

    /**
     * @Description: 删除导入阶段事件。
     * @Logic: 阶段事件属于历史导入观测数据，可随导入任务一起清理。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteStageEvents();

    /**
     * @Description: 删除导入任务。
     * @Logic: 导入任务绑定报告与上传文件路径，重建时重新创建。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteIngestJobs();

    /**
     * @Description: 删除导入失败记录。
     * @Logic: 失败记录属于历史导入诊断，可随历史数据清空。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteIngestFailures();

    /**
     * @Description: 删除 chunk 诊断。
     * @Logic: 诊断依赖 reportId 和 chunkUid，必须先于报告主档清理。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteChunkDiagnostics();

    /**
     * @Description: 删除最终 chunk。
     * @Logic: chunk 通过外键依赖 report_document，必须先于报告主档清理。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteChunks();

    /**
     * @Description: 删除段落 atom。
     * @Logic: atom 通过外键依赖 report_document，必须先于报告主档清理。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteParagraphAtoms();

    /**
     * @Description: 删除 OCR 页。
     * @Logic: OCR 页通过外键依赖 report_document，必须先于报告主档清理。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteOcrPages();

    /**
     * @Description: 删除研报作者。
     * @Logic: 作者通过外键依赖 report_document，必须先于报告主档清理。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteDocumentAuthors();

    /**
     * @Description: 删除研报主档。
     * @Logic: 所有依赖表清理完成后删除报告主档。
     * @Param: 无。
     * @Return: 受影响行数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    int deleteDocuments();
}
