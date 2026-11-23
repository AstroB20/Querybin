package com.example.apibuilder.shared;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.apibuilder.shared.QueryCompiler.CompiledQuery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Component;

/** Runs a {@link CompiledQuery} against the connected database and maps each row into a plain map. */
@Component
public class QueryRunner {

    private final JdbcClient jdbc;

    public QueryRunner(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> run(CompiledQuery query) {
        return jdbc.sql(query.sql()).params(query.args()).query(QueryRunner::row).list();
    }

    private static Map<String, Object> row(ResultSet rs, int rowNum) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            row.put(metaData.getColumnLabel(i), zoneless(JdbcUtils.getResultSetValue(rs, i)));
        }
        return row;
    }

    /**
     * The driver returns legacy java.sql types, which serialize as UTC instants and so shift by the
     * server's time zone. Dates and timestamps without a zone must come back exactly as stored.
     */
    private static Object zoneless(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime();
        }
        return value;
    }
}
