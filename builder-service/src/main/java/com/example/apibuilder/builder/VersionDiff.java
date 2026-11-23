package com.example.apibuilder.builder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.example.apibuilder.builder.ApiRepository.Version;
import com.example.apibuilder.shared.QueryDefinition;
import com.example.apibuilder.shared.QueryDefinition.Filter;

/** Explains what changed between two published versions of an API. */
public final class VersionDiff {

    public enum Kind { ADDED, REMOVED, CHANGED }

    public record Change(String field, Kind kind, String before, String after) {
    }

    private VersionDiff() {
    }

    static List<Change> between(Version from, Version to) {
        QueryDefinition a = from.definition();
        QueryDefinition b = to.definition();
        List<Change> changes = new ArrayList<>();

        changed(changes, "description", from.description(), to.description());
        changed(changes, "table", a.table(), b.table());

        membership(changes, "column", a.columns(), b.columns());
        if (a.columns().size() == b.columns().size() && new HashSet<>(a.columns()).equals(new HashSet<>(b.columns()))) {
            changed(changes, "column order", String.join(", ", a.columns()), String.join(", ", b.columns()));
        }

        membership(changes, "filter", a.filters().stream().map(VersionDiff::describe).toList(),
                b.filters().stream().map(VersionDiff::describe).toList());

        changed(changes, "sort", describeSort(a), describeSort(b));
        changed(changes, "limit", String.valueOf(a.limit()), String.valueOf(b.limit()));
        return changes;
    }

    private static void changed(List<Change> changes, String field, String before, String after) {
        if (!Objects.equals(before, after)) {
            changes.add(new Change(field, Kind.CHANGED, before, after));
        }
    }

    private static void membership(List<Change> changes, String field, List<String> before, List<String> after) {
        before.stream().filter(item -> !after.contains(item))
                .forEach(item -> changes.add(new Change(field, Kind.REMOVED, item, null)));
        after.stream().filter(item -> !before.contains(item))
                .forEach(item -> changes.add(new Change(field, Kind.ADDED, null, item)));
    }

    private static String describe(Filter filter) {
        String start = filter.column() + " " + filter.operator() + " ";
        if (filter.param() == null) {
            return start + "'" + filter.value() + "'";
        }
        return start + ":" + filter.param() + (Boolean.TRUE.equals(filter.required()) ? " (required)" : " (optional)");
    }

    private static String describeSort(QueryDefinition definition) {
        return definition.sort().isEmpty() ? null : definition.sort().stream()
                .map(sort -> sort.column() + " " + sort.direction())
                .collect(Collectors.joining(", "));
    }
}
