package com.example.apibuilder.shared;

import java.util.List;

/**
 * The structured query behind a published API. It is stored as JSON and only ever turned into SQL by
 * {@link QueryCompiler}, so callers never send SQL text.
 */
public record QueryDefinition(String table, List<String> columns, List<Filter> filters, List<Sort> sort,
                              Integer limit) {

    public enum Operator {
        EQ("="), NE("<>"), GT(">"), GTE(">="), LT("<"), LTE("<="), CONTAINS(null), STARTS_WITH(null);

        private final String sql;

        Operator(String sql) {
            this.sql = sql;
        }

        String sql() {
            return sql;
        }

        boolean textOnly() {
            return sql == null;
        }
    }

    public enum Direction { ASC, DESC }

    /**
     * Compares a column with either a fixed {@code value} or a caller-supplied query parameter named
     * {@code param}. An optional parameter that the caller leaves out simply drops the filter.
     */
    public record Filter(String column, Operator operator, String value, String param, Boolean required) {
    }

    public record Sort(String column, Direction direction) {
    }
}
