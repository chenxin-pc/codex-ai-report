package com.example.aimilvusweb.service.ingest;

import com.example.aimilvusweb.common.util.TextEncodingRepairUtils;
import com.example.aimilvusweb.entity.ReportDocumentAuthor;
import com.example.aimilvusweb.repository.ReportDocumentAuthorMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @Description: 研报作者元数据服务，负责解析导入作者、保存 MySQL 事实数据并提供 Milvus 投影数据。
 * @Logic: 输入作者先修复编码并拆分去重，再写入 report_document_author；读取时按 MySQL 事实数据组装多作者 metadata。
 * @Param: 详见各方法入参。
 * @Return: 作者保存数量或作者 metadata。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
@Service
public class ReportAuthorService {

    /** 作者输入分隔符，兼容逗号、顿号、分号、斜线、竖线和换行。 */
    private static final String AUTHOR_SPLIT_REGEX = "[,，、;；/|\\n\\r]+";
    /** Milvus 多作者投影分隔符，两侧补分隔符便于 like 命中完整作者名。 */
    private static final String AUTHOR_TEXT_SEPARATOR = "|";

    /** 研报作者 Mapper，用于 MySQL 作者事实数据读写。 */
    private final ReportDocumentAuthorMapper authorMapper;

    /**
     * @Description: 初始化研报作者服务。
     * @Logic: 保存作者 Mapper，后续导入和向量投影共享同一事实来源。
     * @Param: authorMapper 研报作者 Mapper。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public ReportAuthorService(ReportDocumentAuthorMapper authorMapper) {
        this.authorMapper = authorMapper;
    }

    /**
     * @Description: 保存导入阶段提供的作者信息。
     * @Logic: 先删除同 reportId 旧作者以保障重试幂等，再按输入顺序写入去重后的作者集合；空输入表示空作者集合。
     * @Param: reportId 研报主档 ID；authorTags 导入请求或任务快照中的作者文本。
     * @Return: 实际写入的作者数量。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Transactional
    public int saveImportAuthors(Long reportId, String authorTags) {
        // 清理同一报告旧作者，保证 OCR 阶段重试不会产生重复作者记录。
        authorMapper.deleteByReportId(reportId);
        // 解析并去重作者输入，空输入会得到空集合。
        List<AuthorName> authorNames = parseAuthorNames(authorTags);
        // 初始化写入时间，保证同一批作者 createdAt 一致。
        Instant now = Instant.now();
        // 按顺序写入作者记录，并累加受影响行数。
        int inserted = 0;
        // 遍历去重后的作者集合。
        for (int index = 0; index < authorNames.size(); index++) {
            // 构造单条作者实体，保留原始展示名和规范化名称。
            ReportDocumentAuthor author = new ReportDocumentAuthor();
            // 绑定研报主档 ID。
            author.setReportId(reportId);
            // 保存可展示作者名。
            author.setAuthorName(authorNames.get(index).displayName());
            // 保存过滤和去重使用的规范化作者名。
            author.setNormalizedAuthorName(authorNames.get(index).normalizedName());
            // 保存导入顺序，保证多作者展示稳定。
            author.setAuthorOrder(index);
            // 保存创建时间用于追溯。
            author.setCreatedAt(now);
            // 写入 MySQL 作者事实表。
            inserted += authorMapper.insert(author);
        }
        // 返回写入数量，便于测试和阶段诊断。
        return inserted;
    }

    /**
     * @Description: 查询指定研报的作者 metadata。
     * @Logic: 从 MySQL 作者事实表读取作者列表，并构造 primaryAuthor、作者列表和 Milvus 可过滤文本。
     * @Param: reportId 研报主档 ID。
     * @Return: 作者 metadata；无作者时返回空集合和空文本。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public AuthorMetadata metadataForReport(Long reportId) {
        // reportId 缺失时无法查询作者，按空作者集合处理。
        if (reportId == null) {
            return AuthorMetadata.empty();
        }
        // 从 MySQL 作者事实表读取稳定排序后的作者记录。
        List<ReportDocumentAuthor> authors = authorMapper.selectByReportId(reportId);
        // 作者表无记录表示明确的空作者集合。
        if (authors == null || authors.isEmpty()) {
            return AuthorMetadata.empty();
        }
        // 初始化展示名列表。
        List<String> displayNames = new ArrayList<>(authors.size());
        // 初始化规范化名列表。
        List<String> normalizedNames = new ArrayList<>(authors.size());
        // 遍历作者记录并过滤异常空值。
        for (ReportDocumentAuthor author : authors) {
            // 读取展示名，空值回退为空字符串。
            String displayName = safeText(author.getAuthorName());
            // 读取规范化名，空值时用展示名再规范化。
            String normalizedName = safeText(author.getNormalizedAuthorName());
            // 作者名为空时跳过，避免污染 Milvus metadata。
            if (displayName.isBlank() && normalizedName.isBlank()) {
                continue;
            }
            // 展示名为空时使用规范化名兜底。
            displayNames.add(displayName.isBlank() ? normalizedName : displayName);
            // 规范化名为空时重新从展示名生成。
            normalizedNames.add(normalizedName.isBlank() ? normalizeAuthorName(displayName) : normalizedName);
        }
        // 所有记录都为空时返回空作者集合。
        if (displayNames.isEmpty()) {
            return AuthorMetadata.empty();
        }
        // 构造多作者过滤文本，格式为 |author1|author2|，便于 Milvus like 表达式命中完整值。
        String authorText = AUTHOR_TEXT_SEPARATOR + String.join(AUTHOR_TEXT_SEPARATOR, normalizedNames) + AUTHOR_TEXT_SEPARATOR;
        // 返回不可变作者 metadata。
        return new AuthorMetadata(displayNames.get(0), List.copyOf(displayNames), normalizedNames.get(0), List.copyOf(normalizedNames), authorText);
    }

    /**
     * @Description: 解析作者输入文本。
     * @Logic: 按常见分隔符拆分作者，修复编码、去空白并按规范化名称去重。
     * @Param: authorTags 原始作者输入。
     * @Return: 去重后的作者名称列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    List<AuthorName> parseAuthorNames(String authorTags) {
        // 空输入表示空作者集合。
        if (authorTags == null || authorTags.isBlank()) {
            return List.of();
        }
        // LinkedHashMap 按首次出现顺序去重。
        Map<String, AuthorName> deduplicated = new LinkedHashMap<>();
        // 拆分多作者输入。
        String[] parts = authorTags.split(AUTHOR_SPLIT_REGEX);
        // 遍历拆分结果。
        for (String part : parts) {
            // 修复编码并清理首尾空白。
            String displayName = repairText(part);
            // 空作者没有业务意义，直接跳过。
            if (displayName.isBlank()) {
                continue;
            }
            // 生成过滤和去重使用的规范化作者名。
            String normalizedName = normalizeAuthorName(displayName);
            // 规范化结果为空时跳过，避免写入不可检索数据。
            if (normalizedName.isBlank()) {
                continue;
            }
            // 首次出现的作者保留，重复作者不再写入。
            deduplicated.putIfAbsent(normalizedName, new AuthorName(displayName, normalizedName));
        }
        // 返回稳定顺序作者列表。
        return List.copyOf(deduplicated.values());
    }

    /**
     * @Description: 规范化作者名。
     * @Logic: 去除空白并转小写，降低中英文大小写和排版差异对过滤的影响。
     * @Param: authorName 作者展示名。
     * @Return: 规范化作者名。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    String normalizeAuthorName(String authorName) {
        // 空作者名统一回退为空字符串。
        if (authorName == null) {
            return "";
        }
        // 去除所有空白并统一英文大小写。
        return authorName.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * @Description: 修复并清洗作者文本。
     * @Logic: null 回退为空字符串，非空文本先 trim 再修复 UTF-8 误解码。
     * @Param: value 原始作者文本。
     * @Return: 可入库作者文本。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String repairText(String value) {
        // null 输入不进入编码修复，直接作为空文本处理。
        if (value == null) {
            return "";
        }
        // 调用既有工具修复上传表单可能出现的 mojibake。
        return TextEncodingRepairUtils.repairMojibake(value.trim());
    }

    /**
     * @Description: 安全文本兜底。
     * @Logic: null 转为空字符串，非空文本只做 trim，避免读取 metadata 时 NPE。
     * @Param: value 原始文本。
     * @Return: 非 null 文本。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String safeText(String value) {
        // null 值统一回退为空字符串。
        return value == null ? "" : value.trim();
    }

    /**
     * @Description: 单个作者解析结果。
     * @Logic: 同时保存展示名与规范化名，避免保存阶段重复计算。
     * @Param: displayName 展示作者名；normalizedName 规范化作者名。
     * @Return: 无（仅数据载体）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    record AuthorName(String displayName, String normalizedName) {
    }

    /**
     * @Description: 作者 metadata 投影对象。
     * @Logic: MySQL 作者事实数据转换为 Milvus metadata 和检索过滤可直接消费的字段。
     * @Param: primaryAuthor 首个展示作者；authors 展示作者列表；primaryNormalizedAuthor 首个规范化作者；normalizedAuthors 规范化作者列表；authorText Milvus 多作者过滤文本。
     * @Return: 无（仅数据载体）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public record AuthorMetadata(
            /** 首个展示作者，用于单值 metadata 和前端展示。 */
            String primaryAuthor,
            /** 所有展示作者，用于诊断和可读 metadata。 */
            List<String> authors,
            /** 首个规范化作者，用于单值 author filter。 */
            String primaryNormalizedAuthor,
            /** 所有规范化作者，用于多值 OR filter。 */
            List<String> normalizedAuthors,
            /** Milvus 多作者过滤文本，格式为 |author1|author2|。 */
            String authorText
    ) {
        /**
         * @Description: 构造空作者 metadata。
         * @Logic: 无作者时保持字段为空值或空集合，禁止伪造作者。
         * @Return: 空作者 metadata。
         * @author: cx
         * @Date: 2026-05-31 00:00:00
         */
        private static AuthorMetadata empty() {
            return new AuthorMetadata("", List.of(), "", List.of(), "");
        }
    }
}
