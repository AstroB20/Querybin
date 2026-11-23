package com.example.apibuilder.runtime;

import static com.example.apibuilder.shared.ApiErrors.badRequest;
import static com.example.apibuilder.shared.ApiErrors.notFound;

import com.example.apibuilder.shared.QueryDefinition;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Talks to the builder service to resolve the stored definition behind a published API. */
@Component
public class BuilderClient {

    /** Mirrors the builder's {@code ResolvedApi}: the slug, the resolved version and its definition. */
    public record ResolvedApi(String slug, int version, QueryDefinition definition) {
    }

    /** Just the field we want out of an RFC 7807 problem-detail body; other fields are ignored. */
    private record Problem(String detail) {
    }

    private final RestClient client;
    private final JsonMapper json;
    private final String internalToken;

    public BuilderClient(JsonMapper json,
                         @Value("${builder.base-url:http://127.0.0.1:8080}") String baseUrl,
                         @Value("${builder.internal-token:}") String internalToken) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.json = json;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public ResolvedApi resolve(String slug, Integer version) {
        try {
            return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/apis/{slug}/resolve")
                            .queryParamIfPresent("version", java.util.Optional.ofNullable(version))
                            .build(slug))
                    .headers(headers -> {
                        if (!internalToken.isEmpty()) {
                            headers.add("X-Internal-Token", internalToken);
                        }
                    })
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            ResolvedApi resolved = response.bodyTo(ResolvedApi.class);
                            if (resolved == null) {
                                throw unavailable("The builder service returned an empty response.");
                            }
                            return resolved;
                        }
                        String detail = detail(response.bodyTo(String.class));
                        if (status.value() == HttpStatus.NOT_FOUND.value()) {
                            throw notFound(detail != null ? detail
                                    : "No API named '" + slug + "' has been published.");
                        }
                        if (status.is4xxClientError()) {
                            throw badRequest(detail != null ? detail : "The builder rejected the request.");
                        }
                        throw unavailable("The builder service reported an error.");
                    });
        } catch (ResourceAccessException e) {
            throw unavailable("The builder service is unavailable.");
        }
    }

    private String detail(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Problem problem = json.readValue(body, Problem.class);
            return problem != null ? problem.detail() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
