package com.example.apibuilder.builder;

import java.util.List;
import java.util.Map;

import com.example.apibuilder.builder.ApiBuilderService.ApiDetail;
import com.example.apibuilder.builder.ApiBuilderService.ApiSummary;
import com.example.apibuilder.builder.ApiBuilderService.Diff;
import com.example.apibuilder.builder.ApiBuilderService.Preview;
import com.example.apibuilder.builder.CurlBuilder.CurlCommand;
import com.example.apibuilder.builder.CurlBuilder.Shell;
import com.example.apibuilder.shared.QueryDefinition;
import com.example.apibuilder.shared.SchemaService;
import com.example.apibuilder.shared.SchemaService.Table;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Management endpoints: compose queries, publish them, browse their history and build curl commands. */
@RestController
@RequestMapping("/builder")
public class BuilderController {

    public record CreateRequest(String slug, String description, QueryDefinition definition, String note) {
    }

    public record PublishRequest(String description, QueryDefinition definition, String note) {
    }

    public record PreviewRequest(QueryDefinition definition, Map<String, String> params) {
    }

    public record CurlRequest(Integer version, Map<String, String> params, Shell shell) {
    }

    /** Small bit of server config the web page needs, chiefly where published APIs are served. */
    public record UiConfig(String publicBaseUrl) {
    }

    private final ApiBuilderService service;
    private final SchemaService schema;
    private final String publicBaseUrl;

    public BuilderController(ApiBuilderService service, SchemaService schema,
                            @Value("${app.public-base-url:}") String publicBaseUrl) {
        this.service = service;
        this.schema = schema;
        this.publicBaseUrl = publicBaseUrl;
    }

    @GetMapping("/config")
    public UiConfig config() {
        return new UiConfig(baseUrl());
    }

    @GetMapping("/schema")
    public List<Table> schema() {
        return schema.tables();
    }

    @PostMapping("/preview")
    public Preview preview(@RequestBody PreviewRequest request) {
        return service.preview(request.definition(), request.params());
    }

    @GetMapping("/apis")
    public List<ApiSummary> list() {
        return service.list();
    }

    @PostMapping("/apis")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDetail create(@RequestBody CreateRequest request) {
        return service.create(request.slug(), request.description(), request.definition(), request.note());
    }

    @GetMapping("/apis/{slug}")
    public ApiDetail get(@PathVariable String slug) {
        return service.get(slug);
    }

    @PutMapping("/apis/{slug}")
    public ApiDetail publish(@PathVariable String slug, @RequestBody PublishRequest request) {
        return service.publish(slug, request.description(), request.definition(), request.note());
    }

    @DeleteMapping("/apis/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug) {
        service.delete(slug);
    }

    @GetMapping("/apis/{slug}/versions")
    public List<ApiDetail> versions(@PathVariable String slug) {
        return service.versions(slug);
    }

    @GetMapping("/apis/{slug}/versions/{version}")
    public ApiDetail version(@PathVariable String slug, @PathVariable int version) {
        return service.getVersion(slug, version);
    }

    @PostMapping("/apis/{slug}/versions/{version}/rollback")
    public ApiDetail rollback(@PathVariable String slug, @PathVariable int version) {
        return service.rollback(slug, version);
    }

    @GetMapping("/apis/{slug}/diff")
    public Diff diff(@PathVariable String slug, @RequestParam int from, @RequestParam int to) {
        return service.diff(slug, from, to);
    }

    @PostMapping("/apis/{slug}/curl")
    public CurlCommand curl(@PathVariable String slug, @RequestBody CurlRequest request) {
        return service.curl(slug, request.version(), request.params(), request.shell(), baseUrl());
    }

    /**
     * Published APIs are reached through the gateway, so the curl command must use the public base URL.
     * When it is not configured we fall back to the current request's host.
     */
    private String baseUrl() {
        if (StringUtils.hasText(publicBaseUrl)) {
            return publicBaseUrl.replaceAll("/+$", "");
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
