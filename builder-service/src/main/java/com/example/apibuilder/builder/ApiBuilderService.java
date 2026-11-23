package com.example.apibuilder.builder;

import static com.example.apibuilder.shared.ApiErrors.badRequest;
import static com.example.apibuilder.shared.ApiErrors.conflict;
import static com.example.apibuilder.shared.ApiErrors.notFound;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.example.apibuilder.builder.ApiRepository.Endpoint;
import com.example.apibuilder.builder.ApiRepository.Version;
import com.example.apibuilder.builder.CurlBuilder.CurlCommand;
import com.example.apibuilder.builder.CurlBuilder.Shell;
import com.example.apibuilder.shared.QueryCompiler;
import com.example.apibuilder.shared.QueryCompiler.ApiParameter;
import com.example.apibuilder.shared.QueryCompiler.CompiledQuery;
import com.example.apibuilder.shared.QueryDefinition;
import com.example.apibuilder.shared.QueryRunner;
import com.example.apibuilder.builder.VersionDiff.Change;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiBuilderService {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final int MAX_TEXT = 500;

    public record ApiSummary(String slug, String description, int currentVersion, Instant createdAt,
                             Instant updatedAt) {
    }

    public record ApiDetail(String slug, int version, int currentVersion, String description, String note,
                            Instant publishedAt, QueryDefinition definition, List<ApiParameter> parameters) {
    }

    public record Diff(String slug, int from, int to, List<Change> changes) {
    }

    public record Preview(String sql, List<ApiParameter> parameters, List<Map<String, Object>> rows) {
    }

    /** The resolved definition the runtime service needs to serve a published API. */
    public record ResolvedApi(String slug, int version, QueryDefinition definition) {
    }

    private final ApiRepository repository;
    private final QueryCompiler compiler;
    private final QueryRunner runner;

    public ApiBuilderService(ApiRepository repository, QueryCompiler compiler, QueryRunner runner) {
        this.repository = repository;
        this.compiler = compiler;
        this.runner = runner;
    }

    public List<ApiSummary> list() {
        return repository.findEndpoints().stream()
                .map(e -> new ApiSummary(e.slug(), e.description(), e.currentVersion(), e.createdAt(), e.updatedAt()))
                .toList();
    }

    public ApiDetail get(String slug) {
        return detail(requireVersion(slug, null));
    }

    public ApiDetail getVersion(String slug, int version) {
        return detail(requireVersion(slug, version));
    }

    public List<ApiDetail> versions(String slug) {
        int current = requireEndpoint(slug).currentVersion();
        return repository.findVersions(slug).stream().map(version -> detail(version, current)).toList();
    }

    /** Resolves the stored definition for a published API; a null version means the current one. */
    public ResolvedApi resolve(String slug, Integer version) {
        Version target = requireVersion(slug, version);
        return new ResolvedApi(target.slug(), target.version(), target.definition());
    }

    @Transactional
    public ApiDetail create(String slug, String description, QueryDefinition definition, String note) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw badRequest("The API name must be 1-63 characters of lowercase letters, digits and hyphens, "
                    + "starting with a letter or digit.");
        }
        QueryDefinition normalized = compiler.normalize(definition);
        String cleanDescription = clean(description, "Description");
        String cleanNote = clean(note, "Note");
        if (repository.findEndpoint(slug).isPresent()) {
            throw conflict("An API named '" + slug + "' already exists.");
        }
        try {
            repository.insertEndpoint(slug);
        } catch (DuplicateKeyException e) {
            throw conflict("An API named '" + slug + "' already exists.");
        }
        repository.insertVersion(slug, 1, cleanDescription, normalized, cleanNote);
        return get(slug);
    }

    /** Publishes a new version. A null description keeps the previous one; a blank one clears it. */
    @Transactional
    public ApiDetail publish(String slug, String description, QueryDefinition definition, String note) {
        QueryDefinition normalized = compiler.normalize(definition);
        String cleanNote = clean(note, "Note");
        int version = repository.nextVersion(slug).orElseThrow(() -> unknownApi(slug));
        Version previous = repository.findVersion(slug, version - 1).orElseThrow();
        String newDescription = description == null ? previous.description() : clean(description, "Description");
        if (normalized.equals(previous.definition()) && Objects.equals(newDescription, previous.description())) {
            throw conflict("Nothing changed since version " + previous.version() + ", so no new version was published.");
        }
        repository.insertVersion(slug, version, newDescription, normalized, cleanNote);
        return get(slug);
    }

    /** Rolling back never rewrites history: the old version is published again as the newest one. */
    @Transactional
    public ApiDetail rollback(String slug, int version) {
        Version target = requireVersion(slug, version);
        String description = target.description() == null ? "" : target.description();
        return publish(slug, description, target.definition(), "Rolled back to version " + version);
    }

    public void delete(String slug) {
        if (!repository.delete(slug)) {
            throw unknownApi(slug);
        }
    }

    public Diff diff(String slug, int from, int to) {
        return new Diff(slug, from, to, VersionDiff.between(requireVersion(slug, from), requireVersion(slug, to)));
    }

    public Preview preview(QueryDefinition definition, Map<String, String> params) {
        CompiledQuery query = compiler.compile(definition, params == null ? Map.of() : params);
        return new Preview(query.sql(), compiler.parameters(query.definition()), runner.run(query));
    }

    public CurlCommand curl(String slug, Integer version, Map<String, String> params, Shell shell, String baseUrl) {
        Version target = requireVersion(slug, version);
        return CurlBuilder.build(baseUrl, slug, version, compiler.parameters(target.definition()), params, shell);
    }

    private Endpoint requireEndpoint(String slug) {
        return repository.findEndpoint(slug).orElseThrow(() -> unknownApi(slug));
    }

    private Version requireVersion(String slug, Integer version) {
        int wanted = version != null ? version : requireEndpoint(slug).currentVersion();
        return repository.findVersion(slug, wanted).orElseThrow(() -> repository.findEndpoint(slug).isPresent()
                ? notFound("API '" + slug + "' has no version " + wanted + ".") : unknownApi(slug));
    }

    private ApiDetail detail(Version version) {
        return detail(version, requireEndpoint(version.slug()).currentVersion());
    }

    private ApiDetail detail(Version version, int currentVersion) {
        return new ApiDetail(version.slug(), version.version(), currentVersion, version.description(),
                version.note(), version.createdAt(), version.definition(), compiler.parameters(version.definition()));
    }

    private static String clean(String text, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_TEXT) {
            throw badRequest(label + " must be at most " + MAX_TEXT + " characters.");
        }
        return trimmed;
    }

    private static RuntimeException unknownApi(String slug) {
        return notFound("No API named '" + slug + "' has been published.");
    }
}
