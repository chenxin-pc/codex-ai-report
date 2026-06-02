package com.example.aimilvusweb.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

/**
 * @Description: 补齐 schema.sql 无法安全表达的兼容性字段迁移。
 * @Logic: 启动后通过 JDBC 元数据检查字段是否存在，仅对旧库执行普通 ALTER，避免 MySQL 方言不兼容。
 * @author: Codex
 * @Date: 2026-05-24 18:40:00
 */
@Component
public class SchemaMigrationRunner implements InitializingBean {

    /** 导入任务表名。 */
    private static final String INGEST_JOB_TABLE = "ingest_job";
    /** 导入任务显式标签字段定义。 */
    private static final Map<String, String> INGEST_JOB_TAG_COLUMNS = Map.of(
            "theme_tags", "VARCHAR(1024) NULL",
            "industry_tags", "VARCHAR(1024) NULL",
            "company_tags", "VARCHAR(1024) NULL",
            "ticker_tags", "VARCHAR(1024) NULL",
            "author_tags", "VARCHAR(1024) NULL"
    );

    /** JDBC 执行器。 */
    private final JdbcTemplate jdbcTemplate;
    /** 数据源，用于读取数据库元数据。 */
    private final DataSource dataSource;

    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        ensureIngestJobTagColumns();
    }

    /**
     * @Description: 为旧版 ingest_job 表补齐导入标签字段。
     * @Logic: 新库会由 schema.sql 直接建出字段；旧库只对缺失字段执行 ALTER。
     */
    private void ensureIngestJobTagColumns() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, INGEST_JOB_TABLE)) {
                return;
            }
            for (Map.Entry<String, String> column : INGEST_JOB_TAG_COLUMNS.entrySet()) {
                if (!columnExists(connection, INGEST_JOB_TABLE, column.getKey())) {
                    jdbcTemplate.execute("ALTER TABLE " + INGEST_JOB_TABLE + " ADD COLUMN " + column.getKey() + " " + column.getValue());
                }
            }
        }
    }

    /**
     * @Description: 判断指定表是否存在。
     * @Logic: 同时兼容 MySQL 小写表名和 H2 大写元数据。
     */
    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();
        return hasTable(metaData, catalog, tableName)
                || hasTable(metaData, catalog, tableName.toUpperCase(Locale.ROOT))
                || hasTable(metaData, catalog, tableName.toLowerCase(Locale.ROOT));
    }

    /**
     * @Description: 判断指定字段是否存在。
     * @Logic: 同时尝试原始、小写和大写名称，避免不同数据库元数据大小写差异。
     */
    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();
        return hasColumn(metaData, catalog, tableName, columnName)
                || hasColumn(metaData, catalog, tableName.toUpperCase(Locale.ROOT), columnName.toUpperCase(Locale.ROOT))
                || hasColumn(metaData, catalog, tableName.toLowerCase(Locale.ROOT), columnName.toLowerCase(Locale.ROOT));
    }

    private boolean hasTable(DatabaseMetaData metaData, String catalog, String tableName) throws SQLException {
        try (ResultSet resultSet = metaData.getTables(catalog, null, tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private boolean hasColumn(DatabaseMetaData metaData, String catalog, String tableName, String columnName) throws SQLException {
        try (ResultSet resultSet = metaData.getColumns(catalog, null, tableName, columnName)) {
            return resultSet.next();
        }
    }
}
