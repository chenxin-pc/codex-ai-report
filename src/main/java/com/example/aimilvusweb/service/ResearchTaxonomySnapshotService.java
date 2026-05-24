package com.example.aimilvusweb.service;

import com.example.aimilvusweb.entity.ResearchDictionaryTerm;
import com.example.aimilvusweb.repository.ResearchTaxonomyMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @Description: 投研结构化词库快照服务，加载 ACTIVE 词库并构建可复用的内存匹配索引。
 * @Logic: 查询数据库词库后构建 Trie 快照；加载失败时保留上一份可用快照，避免 query 和标签抽取链路整体不可用。
 * @Param: 详见方法签名；无入参时为无。
 * @Return: 词库快照、匹配结果或版本状态。
 * @author: cx
 * @Date: 2026-05-24 00:00:00
 */
@Service
public class ResearchTaxonomySnapshotService {

    /** 默认词库版本，用于数据库尚未初始化时的兜底。 */
    private static final String DEFAULT_VERSION = "v1";
    /** 词条分隔符集合，用于拆分行业 alias 等多值字段。 */
    private static final String TERM_SPLIT_REGEX = "[,，、/|\\s]+";

    /** 词库 Mapper，提供 ACTIVE 词条和版本读取能力。 */
    private final ResearchTaxonomyMapper researchTaxonomyMapper;
    /** 当前可用快照，加载失败时继续保留旧快照。 */
    private final AtomicReference<TaxonomySnapshot> currentSnapshot = new AtomicReference<>();

    /**
     * @Description: 初始化词库快照服务依赖。
     * @Logic: 保存词库 Mapper，快照按首次请求懒加载，避免启动时数据库波动阻断应用启动。
     * @Param: researchTaxonomyMapper 结构化词库 Mapper。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public ResearchTaxonomySnapshotService(ResearchTaxonomyMapper researchTaxonomyMapper) {
        this.researchTaxonomyMapper = researchTaxonomyMapper;
    }

    /**
     * @Description: 获取当前可用词库快照。
     * @Logic: 优先返回内存快照；不存在时触发刷新；刷新失败仍返回空快照，保证调用方可降级执行。
     * @Param: 无。
     * @Return: 当前词库快照。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public TaxonomySnapshot currentSnapshot() {
        // 优先复用已构建快照，避免每次 query 都访问数据库。
        TaxonomySnapshot snapshot = currentSnapshot.get();
        // 首次访问没有快照时尝试刷新。
        if (snapshot != null) {
            return snapshot;
        }
        // 刷新失败时 refresh 会自行构造空快照，保证返回非 null。
        return refreshSnapshot();
    }

    /**
     * @Description: 重新加载 ACTIVE 词库快照。
     * @Logic: 从数据库读取统一词条，规范化并按长度降序构建 Trie；异常时保留旧快照或返回空快照。
     * @Param: 无。
     * @Return: 新快照；刷新失败时返回旧快照或空快照。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public TaxonomySnapshot refreshSnapshot() {
        try {
            // 读取词库版本，后续标签和 job 都会记录该版本。
            String version = safeVersion(researchTaxonomyMapper.selectActiveDictionaryVersion());
            // 读取 ACTIVE 词条并展开 aliases 这类多值字段。
            List<TaxonomyEntry> entries = expandTerms(researchTaxonomyMapper.selectActiveDictionaryTerms());
            // 将长词优先放入 Trie，有助于命中“电化学储能”而不是只命中“储能”。
            entries = entries.stream()
                    .sorted(Comparator.comparingInt((TaxonomyEntry entry) -> entry.normalizedTerm().length()).reversed())
                    .toList();
            // 构建新快照并替换原子引用。
            TaxonomySnapshot snapshot = new TaxonomySnapshot(version, Instant.now(), entries, new TermMatcher(entries));
            currentSnapshot.set(snapshot);
            return snapshot;
        } catch (RuntimeException e) {
            // 数据库异常时保留上一份快照，避免线上检索和打标链路被瞬时故障击穿。
            TaxonomySnapshot fallback = currentSnapshot.get();
            return fallback == null ? TaxonomySnapshot.empty(DEFAULT_VERSION) : fallback;
        }
    }

    /**
     * @Description: 使用当前词库快照匹配文本中的结构化标签。
     * @Logic: 对输入文本做统一规范化后走 Trie 扫描，返回按标签去重后的命中结果。
     * @Param: text 待匹配文本，通常是 query、标题、sectionPath 或 chunk 正文。
     * @Return: 标签命中列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public List<TaxonomyMatch> match(String text) {
        // 空文本不进入匹配器，避免无意义分配。
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 委托快照内匹配器完成 Trie 扫描。
        return currentSnapshot().matcher().match(text);
    }

    /**
     * @Description: 展开数据库词条为可匹配词条。
     * @Logic: 行业 aliases 可能是一行多词，需拆分；空词、排除词和重复词会被过滤。
     * @Param: terms 数据库查询出的词条列表。
     * @Return: 可进入 Trie 的词条列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private List<TaxonomyEntry> expandTerms(List<ResearchDictionaryTerm> terms) {
        // 使用 LinkedHashMap 保持首个命中顺序，同时对 tag+term 去重。
        Map<String, TaxonomyEntry> deduplicated = new LinkedHashMap<>();
        // 数据库无词条时返回空列表。
        if (terms == null) {
            return List.of();
        }
        // 逐条展开数据库词条。
        for (ResearchDictionaryTerm term : terms) {
            // EXCLUDE 关系用于覆盖分惩罚，不进入正向召回匹配。
            if ("EXCLUDE".equalsIgnoreCase(safeText(term.getRelationType()))) {
                continue;
            }
            // aliases 可能包含多个词，统一拆分后逐个建立匹配项。
            for (String rawTerm : splitTerms(term.getTermText())) {
                // 规范化后为空的词没有匹配意义。
                String normalizedTerm = normalize(rawTerm);
                if (normalizedTerm.isBlank()) {
                    continue;
                }
                // key 包含标签和词条，允许不同主题共享同一个表面词。
                String key = safeText(term.getTagType()) + "|" + safeText(term.getTagCode()) + "|" + normalizedTerm;
                // 首次出现的词条进入快照，重复 seed 或别名不重复写入。
                deduplicated.putIfAbsent(key, new TaxonomyEntry(
                        safeText(term.getTagType()),
                        safeText(term.getTagCode()),
                        safeText(term.getTagName()),
                        rawTerm,
                        normalizedTerm,
                        safeText(term.getRelationType()),
                        term.getWeight() == null ? BigDecimal.ONE : term.getWeight(),
                        safeVersion(term.getDictionaryVersion())
                ));
            }
        }
        return new ArrayList<>(deduplicated.values());
    }

    /**
     * @Description: 拆分可能包含多个 alias 的词条文本。
     * @Logic: 兼容逗号、顿号、斜杠、竖线和空白分隔；普通词条返回自身。
     * @Param: rawTerm 原始词条文本。
     * @Return: 拆分后的词条列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private List<String> splitTerms(String rawTerm) {
        // 空输入直接返回空列表。
        if (rawTerm == null || rawTerm.isBlank()) {
            return List.of();
        }
        // 按多种常见分隔符拆分 alias。
        String[] parts = rawTerm.split(TERM_SPLIT_REGEX);
        // 收集非空词条并保持原始顺序。
        List<String> values = new ArrayList<>();
        // 遍历拆分结果并去除首尾空白。
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        // 如果拆分后为空，保留原始词条作为兜底。
        return values.isEmpty() ? List.of(rawTerm.trim()) : values;
    }

    /**
     * @Description: 标准化词库版本。
     * @Logic: 空版本统一回退为 v1，避免 job 和标签记录出现空版本。
     * @Param: version 原始版本。
     * @Return: 非空词库版本。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private String safeVersion(String version) {
        return version == null || version.isBlank() ? DEFAULT_VERSION : version.trim();
    }

    /**
     * @Description: 标准化匹配文本。
     * @Logic: null 转空字符串，移除空白并转小写，减少版式空格和大小写差异。
     * @Param: text 原始文本。
     * @Return: 规范化文本。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * @Description: 安全文本兜底。
     * @Logic: null 转空字符串，非空文本去除首尾空白。
     * @Param: text 原始文本。
     * @Return: 非 null 文本。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    /**
     * @Description: 词库快照对象，封装版本、加载时间、词条和匹配器。
     * @Logic: 服务层通过不可变快照读取词库状态，避免请求间共享可变集合。
     * @Param: dictionaryVersion 词库版本；loadedAt 加载时间；entries 词条列表；matcher Trie 匹配器。
     */
    public record TaxonomySnapshot(
            /** 当前快照对应的词库版本。 */
            String dictionaryVersion,
            /** 当前快照加载完成时间。 */
            Instant loadedAt,
            /** 当前快照包含的结构化词条。 */
            List<TaxonomyEntry> entries,
            /** 当前快照构建出的 Trie 匹配器。 */
            TermMatcher matcher
    ) {
        /**
         * @Description: 构造空词库快照。
         * @Logic: 数据库不可用或未初始化时返回空快照，调用方仍可继续降级执行。
         * @Param: version 兜底词库版本。
         * @Return: 空词库快照。
         */
        private static TaxonomySnapshot empty(String version) {
            return new TaxonomySnapshot(version, Instant.now(), List.of(), new TermMatcher(List.of()));
        }
    }

    /**
     * @Description: 词库条目值对象，表示一个可匹配词条到一个结构化标签的映射。
     * @Logic: Trie 叶子节点保存该对象，命中后转换为 TaxonomyMatch。
     * @Param: tagType/tagCode/tagName 标签信息；termText/normalizedTerm 词条信息；relationType/weight/dictionaryVersion 规则信息。
     */
    public record TaxonomyEntry(
            /** 标签类型，例如 THEME、INDUSTRY、COMPANY 或 TICKER。 */
            String tagType,
            /** 标签编码，例如 STORAGE 或 300750.SZ。 */
            String tagCode,
            /** 标签展示名称。 */
            String tagName,
            /** 原始词条文本。 */
            String termText,
            /** 规范化词条文本。 */
            String normalizedTerm,
            /** 词条关系类型。 */
            String relationType,
            /** 词条权重。 */
            BigDecimal weight,
            /** 词库版本。 */
            String dictionaryVersion
    ) {
    }

    /**
     * @Description: 词库命中值对象，表示输入文本命中的结构化标签和触发词条。
     * @Logic: query 锚点抽取、chunk 标签抽取和主题覆盖判断复用该对象。
     * @Param: entry 命中的词库条目；start/end 命中位置。
     */
    public record TaxonomyMatch(
            /** 命中的词库条目。 */
            TaxonomyEntry entry,
            /** 命中词条在规范化文本中的开始位置。 */
            int start,
            /** 命中词条在规范化文本中的结束位置。 */
            int end
    ) {
    }

    /**
     * @Description: Trie 词条匹配器，避免对每次 query 或 chunk 执行逐词全量 contains 循环。
     * @Logic: 构建阶段把词条插入 Trie；匹配阶段沿文本位置逐字符前进并收集叶子节点标签。
     * @Param: 详见构造器；无其他入参。
     * @Return: 匹配结果列表。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public static class TermMatcher {

        /** Trie 根节点。 */
        private final TrieNode root = new TrieNode();

        /**
         * @Description: 初始化 Trie 匹配器。
         * @Logic: 遍历词条列表并插入 Trie，空词条跳过，保证匹配阶段只扫描输入文本。
         * @Param: entries 可匹配词条列表。
         * @Return: 无（仅初始化匹配器状态）。
         * @author: cx
         * @Date: 2026-05-24 00:00:00
         */
        public TermMatcher(List<TaxonomyEntry> entries) {
            // 逐条插入 Trie。
            for (TaxonomyEntry entry : entries) {
                insert(entry);
            }
        }

        /**
         * @Description: 匹配文本中的结构化词条。
         * @Logic: 对规范化文本从每个位置尝试沿 Trie 前进；命中后按标签去重返回。
         * @Param: text 待匹配原文。
         * @Return: 去重后的词条命中列表。
         * @author: cx
         * @Date: 2026-05-24 00:00:00
         */
        public List<TaxonomyMatch> match(String text) {
            // 规范化输入，降低空白和大小写对匹配的影响。
            String normalized = normalize(text);
            // 结果按标签和词条去重，避免重复文本放大标签数量。
            Map<String, TaxonomyMatch> matches = new LinkedHashMap<>();
            // 从每个字符位置尝试 Trie 前缀匹配。
            for (int start = 0; start < normalized.length(); start++) {
                TrieNode node = root;
                for (int end = start; end < normalized.length(); end++) {
                    node = node.children.get(normalized.charAt(end));
                    if (node == null) {
                        break;
                    }
                    if (!node.entries.isEmpty()) {
                        for (TaxonomyEntry entry : node.entries) {
                            String key = entry.tagType() + "|" + entry.tagCode() + "|" + entry.normalizedTerm();
                            matches.putIfAbsent(key, new TaxonomyMatch(entry, start, end + 1));
                        }
                    }
                }
            }
            return new ArrayList<>(matches.values());
        }

        /**
         * @Description: 向 Trie 插入一个词库条目。
         * @Logic: 按规范化词条逐字符创建子节点，词尾节点挂载完整词条。
         * @Param: entry 待插入词库条目。
         * @Return: 无（仅修改 Trie 状态）。
         * @author: cx
         * @Date: 2026-05-24 00:00:00
         */
        private void insert(TaxonomyEntry entry) {
            // 空词条跳过，避免根节点误命中。
            if (entry.normalizedTerm() == null || entry.normalizedTerm().isBlank()) {
                return;
            }
            // 从根节点开始逐字符下钻。
            TrieNode node = root;
            for (int i = 0; i < entry.normalizedTerm().length(); i++) {
                char ch = entry.normalizedTerm().charAt(i);
                node = node.children.computeIfAbsent(ch, ignored -> new TrieNode());
            }
            // 词尾保存条目，允许一个词条映射多个标签。
            node.entries.add(entry);
        }
    }

    /**
     * @Description: Trie 节点对象，保存子节点和当前词尾可命中的词库条目。
     * @Logic: 匹配器内部使用，不暴露给业务服务。
     * @Param: 无。
     * @Return: 无。
     */
    private static class TrieNode {
        /** 子节点映射，key 为规范化文本中的单个字符。 */
        private final Map<Character, TrieNode> children = new LinkedHashMap<>();
        /** 当前节点作为词尾时命中的词库条目集合。 */
        private final Set<TaxonomyEntry> entries = new LinkedHashSet<>();
    }
}
