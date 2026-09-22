package org.example.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * SQLite 数据库初始化配置。
 *
 * <p>数据库文件路径由 application.yml 固定为 ./db/super-biz-agent.db。
 * 启动时创建目录、设置 SQLite 并发参数并幂等执行 schema。</p>
 */
@Component
public class SqliteDatabaseConfig {

    private static final Path DATABASE_DIRECTORY = Path.of("./db");
    private static final String SCHEMA_RESOURCE = "db/schema-sqlite.sql";

    private final DataSource dataSource;

    public SqliteDatabaseConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initialize() throws Exception {
        Files.createDirectories(DATABASE_DIRECTORY);
        try (Connection connection = dataSource.getConnection()) {
            configurePragmas(connection);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCHEMA_RESOURCE));
        }
    }

    private void configurePragmas(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }
}
