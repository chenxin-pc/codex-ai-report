package com.example.aimilvusweb.service;

import com.example.aimilvusweb.config.ReportQualityProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @Description: 投研输入护栏词典服务，加载本地规则词和股票代码正则。
 * @Logic: 从配置的 classpath 目录读取固定词典文件；缺失文件降级为空集合，避免启动失败。
 * @author: cx
 * @Date: 2026-05-24 00:45:00
 */
@Service
public class QueryGuardrailDictionaryService {

    /** 不可分析输入短语词典文件名。 */
    private static final String REJECT_PHRASES_FILE = "reject-phrases.txt";
    /** 投研动作词典文件名。 */
    private static final String RESEARCH_ACTIONS_FILE = "research-actions.txt";
    /** 主题研究词典文件名。 */
    private static final String THEME_TERMS_FILE = "theme-terms.txt";
    /** 行业领域词典文件名。 */
    private static final String INDUSTRY_TERMS_FILE = "industry-terms.txt";
    /** 股票代码识别正则词典文件名。 */
    private static final String TICKER_PATTERNS_FILE = "ticker-patterns.txt";
    /** 降级输出禁用投资建议短语词典文件名。 */
    private static final String FORBIDDEN_RECOMMENDATION_PHRASES_FILE = "forbidden-recommendation-phrases.txt";

    /** Spring资源加载器，用于读取 classpath 下的护栏词典文件。 */
    private final ResourceLoader resourceLoader;
    /** 研报质量配置，提供词典目录等 query guardrail 参数。 */
    private final ReportQualityProperties reportQualityProperties;

    /**
     * @Description: 初始化词典加载依赖。
     * @Logic: 保存资源加载器和配置对象，词典在每次读取时按配置路径加载，便于测试覆盖不同配置。
     * @Param: resourceLoader Spring资源加载器；reportQualityProperties 研报质量配置。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public QueryGuardrailDictionaryService(ResourceLoader resourceLoader,
                                           ReportQualityProperties reportQualityProperties) {
        // 保存资源加载器，用于从 classpath 读取词典文件。
        this.resourceLoader = resourceLoader;
        // 保存质量配置，用于读取词典目录路径。
        this.reportQualityProperties = reportQualityProperties;
    }

    /**
     * @Description: 返回不可分析输入词典。
     * @Logic: 加载 reject-phrases.txt，用于识别寒暄、感谢、身份询问等不进入投研链路的输入。
     * @Param: 无。
     * @Return: 不可分析短语列表。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public List<String> rejectPhrases() {
        // 读取不可分析输入短语文件。
        return loadTerms(REJECT_PHRASES_FILE);
    }

    /**
     * @Description: 返回投研动作词典。
     * @Logic: 加载 research-actions.txt，用于识别“分析、估值、风险”等投研动作。
     * @Param: 无。
     * @Return: 投研动作词列表。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public List<String> researchActions() {
        // 读取投研动作词文件。
        return loadTerms(RESEARCH_ACTIONS_FILE);
    }

    /**
     * @Description: 返回主题类词典。
     * @Logic: 加载 theme-terms.txt，用于识别行业、板块、产业链、关注清单等主题研究意图。
     * @Param: 无。
     * @Return: 主题词列表。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public List<String> themeTerms() {
        // 读取主题研究词文件。
        return loadTerms(THEME_TERMS_FILE);
    }

    /**
     * @Description: 返回行业类词典。
     * @Logic: 加载 industry-terms.txt，用于识别用户问题或证据片段中的行业领域。
     * @Param: 无。
     * @Return: 行业词列表。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public List<String> industryTerms() {
        // 读取行业领域词文件。
        return loadTerms(INDUSTRY_TERMS_FILE);
    }

    /**
     * @Description: 返回股票代码匹配正则。
     * @Logic: 加载 ticker-patterns.txt 并编译为大小写不敏感的 Pattern，供输入和证据实体识别使用。
     * @Param: 无。
     * @Return: 股票代码正则列表。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public List<Pattern> tickerPatterns() {
        // 读取股票代码正则词条并编译为 Pattern。
        return loadTerms(TICKER_PATTERNS_FILE).stream()
                // 每条正则使用大小写不敏感模式，兼容 sh/sz/bj 后缀大小写。
                .map(pattern -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE))
                // 收集为正则列表供输入判定使用。
                .toList();
    }

    /**
     * @Description: 返回禁止出现在降级输出中的高确定性推荐短语。
     * @Logic: 加载 forbidden-recommendation-phrases.txt，用于生成后保护和降级输出检查。
     * @Param: 无。
     * @Return: 禁用推荐短语列表。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    public List<String> forbiddenRecommendationPhrases() {
        // 读取降级输出禁用推荐短语文件。
        return loadTerms(FORBIDDEN_RECOMMENDATION_PHRASES_FILE);
    }

    /**
     * @Description: 按文件名读取词典条目。
     * @Logic: 拼接配置目录和文件名后读取 classpath 资源；忽略空行与注释行，异常时返回空集合。
     * @Param: fileName 词典文件名。
     * @Return: 词典条目列表，资源缺失或读取失败时为空列表。
     * @author: cx
     * @Date: 2026-05-24 00:45:00
     */
    private List<String> loadTerms(String fileName) {
        // 从配置读取词典目录，例如 query-guardrail/。
        String dictionaryPath = reportQualityProperties.getQueryGuardrail().getDictionaryPath();
        // 保证目录以斜杠结尾，方便拼接文件名。
        String normalizedPath = dictionaryPath.endsWith("/") ? dictionaryPath : dictionaryPath + "/";
        // 构造 classpath 资源路径。
        Resource resource = resourceLoader.getResource("classpath:" + normalizedPath + fileName);
        // 资源不存在时返回空词典，避免应用启动或请求处理失败。
        if (!resource.exists()) {
            // 空集合表示该类规则不参与匹配。
            return List.of();
        }
        // 读取资源文件可能抛 IOException，因此在本方法内兜底。
        try {
            // 以 UTF-8 读取完整词典文本。
            String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // 按行解析词典条目。
            return text.lines()
                    // 去除每行首尾空白。
                    .map(String::trim)
                    // 忽略空行。
                    .filter(line -> !line.isBlank())
                    // 忽略注释行。
                    .filter(line -> !line.startsWith("#"))
                    // 收集为不可变词条列表。
                    .toList();
        } catch (IOException e) {
            // 词典读取失败时降级为空集合，避免护栏服务中断查询链路。
            return List.of();
        }
    }
}
