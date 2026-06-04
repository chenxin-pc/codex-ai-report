package com.example.aimilvusweb.infra.vector;

import com.example.aimilvusweb.config.MilvusHybridProperties;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;

/**
 * @Description: MilvusReportHybridVectorStore 单元测试，验证 hybrid schema 和 BM25 function 构造。
 * @Logic: 通过反射读取私有 schema 构造方法，不连接真实 Milvus 即可校验字段、analyzer 和 BM25 function。
 * @Param: 无。
 * @Return: 无（仅断言 schema 定义）。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
class MilvusReportHybridVectorStoreTests {

    /**
     * @Description: 验证 schema 包含 dense、sparse、文本和作者字段。
     * @Logic: fieldSchemas 应启用文本 analyzer，并包含 BM25 输出 sparse vector 及作者 metadata。
     * @Param: 无。
     * @Return: 无（仅断言字段定义）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Test
    void shouldBuildHybridSchemaFieldsWithAnalyzerAndAuthorMetadata() throws Exception {
        // 创建默认 hybrid 配置。
        MilvusHybridProperties properties = new MilvusHybridProperties();
        // 创建 Milvus hybrid store。
        MilvusReportHybridVectorStore store = newStore(properties);

        // 反射调用 fieldSchemas 方法。
        List<CreateCollectionReq.FieldSchema> fields = invokeList(store, "fieldSchemas");

        // 提取字段名集合。
        Set<String> fieldNames = fields.stream().map(CreateCollectionReq.FieldSchema::getName).collect(java.util.stream.Collectors.toSet());
        // 主键字段存在。
        Assertions.assertTrue(fieldNames.contains(properties.getPrimaryKeyField()));
        // dense 字段存在。
        Assertions.assertTrue(fieldNames.contains(properties.getDenseVectorField()));
        // sparse 字段存在。
        Assertions.assertTrue(fieldNames.contains(properties.getSparseVectorField()));
        // 作者过滤字段存在。
        Assertions.assertTrue(fieldNames.contains("authorText"));
        // 找到文本字段。
        CreateCollectionReq.FieldSchema textField = fields.stream()
                .filter(field -> properties.getTextField().equals(field.getName()))
                .findFirst()
                .orElseThrow();
        // 文本字段启用 analyzer。
        Assertions.assertEquals(Boolean.TRUE, textField.getEnableAnalyzer());
        // 文本字段启用 match。
        Assertions.assertEquals(Boolean.TRUE, textField.getEnableMatch());
        // 文本字段 analyzer 参数来自配置。
        Assertions.assertEquals(properties.getTextAnalyzerType(), textField.getAnalyzerParams().get("type"));
        // sparse 字段是 SparseFloatVector。
        Assertions.assertEquals(DataType.SparseFloatVector, fields.stream()
                .filter(field -> properties.getSparseVectorField().equals(field.getName()))
                .findFirst()
                .orElseThrow()
                .getDataType());
    }

    /**
     * @Description: 验证 BM25 function 和必需字段集合。
     * @Logic: BM25 function 输入 chunkText、输出 sparseVector，requiredFieldNames 包含不可缺失字段。
     * @Param: 无。
     * @Return: 无（仅断言 function 和字段集合）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Test
    void shouldBuildBm25FunctionAndRequiredFieldSet() throws Exception {
        // 创建默认 hybrid 配置。
        MilvusHybridProperties properties = new MilvusHybridProperties();
        // 创建 Milvus hybrid store。
        MilvusReportHybridVectorStore store = newStore(properties);

        // 反射调用 BM25 function 构造方法。
        CreateCollectionReq.Function function = invokeFunction(store, "bm25Function");
        // 反射调用 requiredFieldNames 方法。
        Set<String> requiredFields = invokeSet(store, "requiredFieldNames");

        // function 类型必须为 BM25。
        Assertions.assertEquals(FunctionType.BM25, function.getFunctionType());
        // function 输入字段是 chunkText。
        Assertions.assertEquals(List.of(properties.getTextField()), function.getInputFieldNames());
        // function 输出字段是 sparseVector。
        Assertions.assertEquals(List.of(properties.getSparseVectorField()), function.getOutputFieldNames());
        // 必需字段包含 dense vector。
        Assertions.assertTrue(requiredFields.contains(properties.getDenseVectorField()));
        // 必需字段包含 sparse vector。
        Assertions.assertTrue(requiredFields.contains(properties.getSparseVectorField()));
        // 必需字段包含作者过滤文本。
        Assertions.assertTrue(requiredFields.contains("authorText"));
    }

    /**
     * @Description: 验证清空动作会覆盖 hybrid 和 legacy collection。
     * @Logic: resetCollectionNames 应包含当前 hybrid collection 和旧 dense-only collection，并保持去重后的稳定顺序。
     * @Param: 无。
     * @Return: 无（仅断言 collection 清单）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Test
    void shouldIncludeHybridAndLegacyCollectionsForReset() throws Exception {
        // 创建 hybrid 配置。
        MilvusHybridProperties properties = new MilvusHybridProperties();
        // 设置旧 collection 名称。
        properties.setLegacyCollectionNames(List.of("report_chunks", "report_chunks_hybrid"));
        // 创建 Milvus hybrid store。
        MilvusReportHybridVectorStore store = newStore(properties);

        // 反射读取清空 collection 名单。
        List<String> collectionNames = invokeList(store, "resetCollectionNames");

        // 第一个 collection 是当前 hybrid collection。
        Assertions.assertEquals("report_chunks_hybrid", collectionNames.get(0));
        // 旧 dense-only collection 包含在清空范围。
        Assertions.assertTrue(collectionNames.contains("report_chunks"));
        // 重复的 hybrid collection 被去重。
        Assertions.assertEquals(2, collectionNames.size());
    }

    /**
     * @Description: 创建 Milvus hybrid store 测试对象。
     * @Logic: 使用 mock embedding provider，schema 构造测试不会访问真实模型或 Milvus。
     * @Param: properties hybrid 配置。
     * @Return: hybrid store。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private MilvusReportHybridVectorStore newStore(MilvusHybridProperties properties) {
        // 创建 embedding provider mock。
        ObjectProvider<EmbeddingModel> embeddingModelProvider = mock(ObjectProvider.class);
        // 返回 store 实例。
        return new MilvusReportHybridVectorStore(properties, embeddingModelProvider);
    }

    /**
     * @Description: 反射调用返回列表的方法。
     * @Logic: 设置 private 方法可访问并转换返回类型。
     * @Param: target 调用对象；methodName 方法名。
     * @Return: 方法返回列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> invokeList(Object target, String methodName) throws Exception {
        // 查找私有方法。
        Method method = target.getClass().getDeclaredMethod(methodName);
        // 允许反射访问。
        method.setAccessible(true);
        // 调用并转换结果。
        return (List<T>) method.invoke(target);
    }

    /**
     * @Description: 反射调用返回集合的方法。
     * @Logic: 设置 private 方法可访问并转换返回类型。
     * @Param: target 调用对象；methodName 方法名。
     * @Return: 方法返回集合。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @SuppressWarnings("unchecked")
    private Set<String> invokeSet(Object target, String methodName) throws Exception {
        // 查找私有方法。
        Method method = target.getClass().getDeclaredMethod(methodName);
        // 允许反射访问。
        method.setAccessible(true);
        // 调用并转换结果。
        return (Set<String>) method.invoke(target);
    }

    /**
     * @Description: 反射调用返回 BM25 function 的方法。
     * @Logic: 设置 private 方法可访问并转换返回类型。
     * @Param: target 调用对象；methodName 方法名。
     * @Return: BM25 function。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private CreateCollectionReq.Function invokeFunction(Object target, String methodName) throws Exception {
        // 查找私有方法。
        Method method = target.getClass().getDeclaredMethod(methodName);
        // 允许反射访问。
        method.setAccessible(true);
        // 调用并转换结果。
        return (CreateCollectionReq.Function) method.invoke(target);
    }
}
