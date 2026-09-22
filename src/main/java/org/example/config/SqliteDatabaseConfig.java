package org.example.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * SQLite 数据库连接配置。
 *
 * <p>数据库文件路径由 application.yml 固定为 ./db/super-biz-agent.db。
 * 数据库初始化和升级脚本由人工执行，本类只负责创建目录和设置 SQLite 连接参数。</p>
 */
@Component
public class SqliteDatabaseConfig {

    private static final Path DATABASE_DIRECTORY = Path.of("./db");
    private final DataSource dataSource;

    public SqliteDatabaseConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initialize() throws Exception {
        Files.createDirectories(DATABASE_DIRECTORY);
        try (Connection connection = dataSource.getConnection()) {
            configurePragmas(connection);
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
