package com.example.apibuilder.runtime;

import java.util.List;
import java.util.Map;

import com.example.apibuilder.runtime.BuilderClient.ResolvedApi;
import com.example.apibuilder.shared.QueryCompiler;
import com.example.apibuilder.shared.QueryCompiler.CompiledQuery;
import com.example.apibuilder.shared.QueryRunner;

import org.springframework.stereotype.Service;

/** Resolves a published API's definition from the builder, compiles it and runs it against the database. */
@Service
public class RuntimeService {

    public record QueryResult(String api, int version, int count, List<Map<String, Object>> data) {
    }

    private final BuilderClient builder;
    private final QueryCompiler compiler;
    private final QueryRunner runner;

    public RuntimeService(BuilderClient builder, QueryCompiler compiler, QueryRunner runner) {
        this.builder = builder;
        this.compiler = compiler;
        this.runner = runner;
    }

    public QueryResult run(String slug, Integer version, Map<String, String> params) {
        ResolvedApi resolved = builder.resolve(slug, version);
        CompiledQuery query = compiler.compile(resolved.definition(), params == null ? Map.of() : params);
        List<Map<String, Object>> rows = runner.run(query);
        return new QueryResult(resolved.slug(), resolved.version(), rows.size(), rows);
    }
}
