package com.example.apibuilder.shared;

import static com.example.apibuilder.shared.ApiErrors.badRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.example.apibuilder.shared.QueryDefinition.Direction;
import com.example.apibuilder.shared.QueryDefinition.Filter;
import com.example.apibuilder.shared.QueryDefinition.Sort;
import com.example.apibuilder.shared.SchemaService.Column;
import com.example.apibuilder.shared.SchemaService.Table;
import com.example.apibuilder.shared.SchemaService.ValueType;

import org.springframework.stereotype.Component;

/**
 * Turns a {@link QueryDefinition} into SQL. Table and column names are only accepted when they match
 * the live schema, and every value is bound as a statement parameter, so no caller text reaches the SQL.
 */
@Component
public class QueryCompiler {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    private static final Pattern PARAM_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,49}");

    /** A query parameter that callers of a published API can supply. */
    public record ApiParameter(String name, ValueType type, boolean required) {
    }

    public record CompiledQuery(String sql, List<Object> args, QueryDefinition definition) {
    }

    private final SchemaService schema;

    public QueryCompiler(SchemaService schema) {
        this.schema = schema;
    }

    /** Validates a definition against the schema and returns it with canonical names and defaults applied. */
    public QueryDefinition normalize(QueryDefinition definition) {
        return normalize(definition, requireTable(definition));
    }

    public CompiledQuery compile(QueryDefinition definition, Map<String, String> params) {
        Table table = requireTable(definition);
        QueryDefinition query = normalize(definition, table);

        List<String> allowed = query.filters().stream().map(Filter::param).filter(p -> p != null).distinct().toList();
        for (String name : params.keySet()) {
            if (!allowed.contains(name)) {
                throw badRequest("Unknown parameter '" + name + "'. " + (allowed.isEmpty()
                        ? "This API takes no parameters." : "Allowed: " + String.join(", ", allowed) + "."));
            }
        }

        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Filter filter : query.filters()) {
            Column column = column(table, filter.column());
            String raw = filter.value();
            String source = "Fixed value";
            if (filter.param() != null) {
                raw = params.get(filter.param());
                source = "Parameter '" + filter.param() + "'";
                if (raw == null || raw.isBlank()) {
                    if (filter.required()) {
                        throw badRequest("Missing required parameter '" + filter.param() + "'.");
                    }
                    continue;
                }
            }
            String quoted = quote(column.name());
            switch (filter.operator()) {
                case CONTAINS -> {
                    conditions.add("LOWER(" + quoted + ") LIKE ? ESCAPE '!'");
                    args.add("%" + likeLiteral(raw) + "%");
                }
                case STARTS_WITH -> {
                    conditions.add("LOWER(" + quoted + ") LIKE ? ESCAPE '!'");
                    args.add(likeLiteral(raw) + "%");
                }
                default -> {
                    conditions.add(quoted + " " + filter.operator().sql() + " ?");
                    args.add(convert(column, raw, source));
                }
            }
        }

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(query.columns().stream().map(this::quote).collect(Collectors.joining(", ")))
                .append(" FROM ").append(schema.qualifiedTable(table.name()));
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        if (!query.sort().isEmpty()) {
            sql.append(" ORDER BY ").append(query.sort().stream()
                    .map(sort -> quote(sort.column()) + " " + sort.direction())
                    .collect(Collectors.joining(", ")));
        }
        sql.append(" LIMIT ").append(query.limit());
        return new CompiledQuery(sql.toString(), args, query);
    }

    /**
     * Lists the parameters a stored definition exposes. Unlike {@link #compile} this never fails, so the
     * history of an API stays readable even if the schema changed after a version was published.
     */
    public List<ApiParameter> parameters(QueryDefinition definition) {
        Optional<Table> table = schema.findTable(definition.table());
        Map<String, ApiParameter> parameters = new LinkedHashMap<>();
        for (Filter filter : definition.filters()) {
            if (filter.param() == null) {
                continue;
            }
            ValueType type = table.flatMap(t -> t.find(filter.column())).map(Column::valueType).orElse(null);
            parameters.merge(filter.param(),
                    new ApiParameter(filter.param(), type, Boolean.TRUE.equals(filter.required())),
                    (first, second) -> new ApiParameter(first.name(), first.type(),
                            first.required() || second.required()));
        }
        return List.copyOf(parameters.values());
    }

    private Table requireTable(QueryDefinition definition) {
        if (definition == null || definition.table() == null || definition.table().isBlank()) {
            throw badRequest("Choose a table to query.");
        }
        return schema.findTable(definition.table())
                .orElseThrow(() -> badRequest("Unknown table '" + definition.table() + "'."));
    }

    private QueryDefinition normalize(QueryDefinition definition, Table table) {
        if (definition.columns() == null || definition.columns().isEmpty()) {
            throw badRequest("Select at least one column.");
        }
        List<String> columns = new ArrayList<>();
        for (String requested : definition.columns()) {
            String name = column(table, requested).name();
            if (columns.contains(name)) {
                throw badRequest("Column '" + name + "' is selected more than once.");
            }
            columns.add(name);
        }

        List<Filter> filters = new ArrayList<>();
        for (Filter filter : orEmpty(definition.filters())) {
            filters.add(normalize(filter, table));
        }

        List<Sort> sort = new ArrayList<>();
        for (Sort entry : orEmpty(definition.sort())) {
            if (entry == null) {
                throw badRequest("Sort entries must not be empty.");
            }
            sort.add(new Sort(column(table, entry.column()).name(),
                    entry.direction() == null ? Direction.ASC : entry.direction()));
        }

        int limit = definition.limit() == null ? DEFAULT_LIMIT : definition.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw badRequest("Limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return new QueryDefinition(table.name(), List.copyOf(columns), List.copyOf(filters), List.copyOf(sort), limit);
    }

    private Filter normalize(Filter filter, Table table) {
        if (filter == null) {
            throw badRequest("Filter entries must not be empty.");
        }
        Column column = column(table, filter.column());
        if (filter.operator() == null) {
            throw badRequest("The filter on '" + column.name() + "' needs an operator.");
        }
        if (column.valueType() == null) {
            throw badRequest("Column '" + column.name() + "' has type " + column.type()
                    + ", which cannot be used in a filter.");
        }
        if (filter.operator().textOnly() && column.valueType() != ValueType.TEXT) {
            throw badRequest(filter.operator() + " only works on text columns, but '" + column.name()
                    + "' has type " + column.type() + ".");
        }
        String param = filter.param() == null || filter.param().isBlank() ? null : filter.param().trim();
        if ((param == null) == (filter.value() == null)) {
            throw badRequest("The filter on '" + column.name()
                    + "' needs either a fixed value or a parameter name, not both.");
        }
        if (param == null) {
            if (!filter.operator().textOnly()) {
                convert(column, filter.value(), "Fixed value");
            }
            return new Filter(column.name(), filter.operator(), filter.value(), null, null);
        }
        if (!PARAM_NAME.matcher(param).matches()) {
            throw badRequest("Parameter name '" + param
                    + "' must start with a letter and contain only letters, digits and underscores.");
        }
        return new Filter(column.name(), filter.operator(), null, param,
                filter.required() == null || filter.required());
    }

    private static Column column(Table table, String name) {
        if (name == null || name.isBlank()) {
            throw badRequest("A column name is missing.");
        }
        return table.find(name)
                .orElseThrow(() -> badRequest("Table '" + table.name() + "' has no column '" + name + "'."));
    }

    private static Object convert(Column column, String raw, String source) {
        try {
            return column.valueType().parse(raw);
        } catch (RuntimeException e) {
            throw badRequest(source + " must be " + column.valueType().expected() + " to compare with '"
                    + column.name() + "', but was '" + raw + "'.");
        }
    }

    /**
     * Lower-cases a search term and escapes LIKE wildcards so it only ever matches literally. The escape
     * character is '!' because backslash is treated differently by different databases.
     */
    private static String likeLiteral(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private String quote(String identifier) {
        return schema.quote(identifier);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
