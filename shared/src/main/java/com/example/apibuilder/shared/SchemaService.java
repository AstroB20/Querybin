package com.example.apibuilder.shared;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * Describes the tables that published APIs may query: one schema of the connected (target) database.
 * By default that is the connection's current schema; choose another with {@code app.target-schema}.
 * Works with databases that namespace tables by schema (PostgreSQL, H2) or by catalog (MySQL, MariaDB).
 */
@Service
public class SchemaService {

    /** Where the queryable tables live and how this database quotes identifiers. */
    private record Target(String catalog, String schema, String quote) {

        String namespace() {
            return schema != null ? schema : catalog;
        }
    }

    private final JdbcTemplate jdbc;
    private final String configuredSchema;
    private volatile Target target;

    public SchemaService(JdbcTemplate jdbc, @Value("${app.target-schema:}") String configuredSchema) {
        this.jdbc = jdbc;
        this.configuredSchema = configuredSchema == null ? "" : configuredSchema.trim();
    }

    /** Quotes an identifier the way the target database expects. */
    public String quote(String identifier) {
        String quote = target().quote();
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    /** The fully qualified, quoted name of a table in the target schema. */
    public String qualifiedTable(String tableName) {
        String namespace = target().namespace();
        return namespace == null ? quote(tableName) : quote(namespace) + "." + quote(tableName);
    }

    /** Resolved on first use, so the service can start before the target database is reachable. */
    private Target target() {
        Target resolved = target;
        if (resolved == null) {
            resolved = jdbc.execute((ConnectionCallback<Target>) this::resolveTarget);
            target = resolved;
        }
        return resolved;
    }

    private Target resolveTarget(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String quote = metaData.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            quote = "\"";
        }
        boolean usesSchemas = metaData.supportsSchemasInTableDefinitions();
        String name;
        if (configuredSchema.isEmpty()) {
            name = usesSchemas ? connection.getSchema() : connection.getCatalog();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("The database connection has no default "
                        + (usesSchemas ? "schema" : "database") + "; set app.target-schema.");
            }
        } else {
            List<String> available = new ArrayList<>();
            try (ResultSet rs = usesSchemas ? metaData.getSchemas() : metaData.getCatalogs()) {
                while (rs.next()) {
                    available.add(rs.getString(1));
                }
            }
            name = match(available, item -> item, configuredSchema).orElseThrow(() -> new IllegalStateException(
                    "app.target-schema '" + configuredSchema + "' does not exist in the target database."));
        }
        return usesSchemas ? new Target(null, name, quote) : new Target(name, null, quote);
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
        Target where = target();
        return jdbc.execute((ConnectionCallback<List<Table>>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            Map<String, List<Column>> columnsByTable = new LinkedHashMap<>();
            try (ResultSet rs = metaData.getColumns(where.catalog(),
                    literalPattern(where.schema(), metaData.getSearchStringEscape()), "%", "%")) {
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

    /** The schema argument of getColumns is a LIKE pattern, so its wildcards must be escaped. */
    private static String literalPattern(String name, String escape) {
        if (name == null || escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
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
