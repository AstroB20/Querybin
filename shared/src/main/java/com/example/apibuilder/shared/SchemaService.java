package com.example.apibuilder.shared;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Describes the tables that published APIs may query. By default this is the {@code PUBLIC} schema of the
 * connected database; point it at another schema with {@code app.target-schema}. The builder's own
 * metadata lives in a separate schema that is never exposed here.
 */
@Service
public class SchemaService {

    private final JdbcTemplate jdbc;
    private final String schema;

    public SchemaService(JdbcTemplate jdbc, @Value("${app.target-schema:PUBLIC}") String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /** The database schema that published APIs are allowed to read. */
    public String schema() {
        return schema;
    }

    /** How a filter value arriving as text is converted before it is bound to the query. */
    public enum ValueType {
        INTEGER("a whole number"),
        DECIMAL("a number"),
        BOOLEAN("true or false"),
        DATE("a date like 2024-01-31"),
        TIMESTAMP("a timestamp like 2024-01-31T10:15:00"),
        TEXT("text");

        private final String expected;

        ValueType(String expected) {
            this.expected = expected;
        }

        public String expected() {
            return expected;
        }

        static ValueType of(int sqlType) {
            return switch (sqlType) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> INTEGER;
                case Types.NUMERIC, Types.DECIMAL, Types.REAL, Types.FLOAT, Types.DOUBLE -> DECIMAL;
                case Types.BOOLEAN, Types.BIT -> BOOLEAN;
                case Types.DATE -> DATE;
                case Types.TIMESTAMP -> TIMESTAMP;
                case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                     Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> TEXT;
                default -> null;
            };
        }

        public Object parse(String raw) {
            return switch (this) {
                case INTEGER -> Long.valueOf(raw.trim());
                case DECIMAL -> new BigDecimal(raw.trim());
                case BOOLEAN -> parseBoolean(raw.trim());
                case DATE -> LocalDate.parse(raw.trim());
                case TIMESTAMP -> LocalDateTime.parse(raw.trim());
                case TEXT -> raw;
            };
        }

        private static Boolean parseBoolean(String raw) {
            if (raw.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (raw.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Not a boolean: " + raw);
        }
    }

    /** {@code valueType} is null when the column's type cannot be used in a filter. */
    public record Column(String name, String type, ValueType valueType) {
    }

    public record Table(String name, List<Column> columns) {

        public Optional<Column> find(String columnName) {
            return match(columns, Column::name, columnName);
        }
    }

    public List<Table> tables() {
        return jdbc.execute((ConnectionCallback<List<Table>>) connection -> {
            Map<String, List<Column>> columnsByTable = new LinkedHashMap<>();
            try (ResultSet rs = connection.getMetaData().getColumns(null, schema, "%", "%")) {
                while (rs.next()) {
                    columnsByTable.computeIfAbsent(rs.getString("TABLE_NAME"), name -> new ArrayList<>())
                            .add(new Column(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"),
                                    ValueType.of(rs.getInt("DATA_TYPE"))));
                }
            }
            List<Table> tables = new ArrayList<>();
            columnsByTable.forEach((name, columns) -> tables.add(new Table(name, List.copyOf(columns))));
            return tables;
        });
    }

    public Optional<Table> findTable(String tableName) {
        return match(tables(), Table::name, tableName);
    }

    /** Prefers an exact match, then falls back to ignoring case so callers need not shout. */
    private static <T> Optional<T> match(List<T> items, java.util.function.Function<T, String> name, String wanted) {
        if (wanted == null) {
            return Optional.empty();
        }
        String trimmed = wanted.trim();
        Optional<T> exact = items.stream().filter(item -> name.apply(item).equals(trimmed)).findFirst();
        return exact.isPresent() ? exact
                : items.stream().filter(item -> name.apply(item).equalsIgnoreCase(trimmed)).findFirst();
    }
}
