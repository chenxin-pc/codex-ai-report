# 注释与 Lombok 规则

## 类与方法注释模板（强制）
```java
/**
 * @Description:
 * @author: cx
 * @Date: yyyy-MM-dd HH:mm:ss
 */
```

## 注释语义要求（强制）
- `@Description` 必须描述真实行为与结果，禁止模板化空话。
- 方法注释应覆盖：做了什么、主要输入对象、输出或副作用。
- `@Date` 必须填写真实创建/修改时间，不允许留空或占位文本。

## 字段注释规则（强制）
- 字段注释必须说明实际业务含义与用途。
- 禁止仅写“模型/参数/状态”等空泛词。
- 必要时补充默认值语义或配置来源。

## Lombok 默认策略（强制）
- 默认使用 Lombok 生成 `get/set`，不手写重复访问器。
- 优先使用 `@Getter` / `@Setter` 的最小范围生成。
- 有业务校验、派生逻辑、兼容逻辑时必须手写方法。

## 常用注解
- `@Getter` / `@Setter`：访问器生成。
- `@ToString`：调试输出；敏感字段必须 `exclude`。
- `@EqualsAndHashCode`：值对象相等性；继承场景显式 `callSuper`。
- `@RequiredArgsConstructor`：Spring Bean 构造注入优先。
- `@Builder`：多参数对象构建，DTO/测试优先。
- `@Slf4j`：日志对象生成。
- `@Data`：仅简单数据载体使用，复杂对象慎用。

## 安全限制
- 涉及密码、密钥、Token、证件号等敏感字段时，禁止泄露在 `toString`。
- 新增或调整 Lombok 注解后，必须确保 `mvn -q test` 通过。
