package com.example.apibuilder.builder;

import com.example.apibuilder.builder.ApiBuilderService.ResolvedApi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service-to-service endpoints. The runtime service calls these to fetch the stored definition behind a
 * published API. They are not part of the public UI. When {@code app.internal-token} is set, callers must
 * present it as the {@code X-Internal-Token} header; leave it unset only on a trusted private network.
 */
@RestController
@RequestMapping("/internal")
public class InternalApiController {

    static final String HEADER = "X-Internal-Token";

    private final ApiBuilderService service;
    private final String token;

    public InternalApiController(ApiBuilderService service, @Value("${app.internal-token:}") String token) {
        this.service = service;
        this.token = token == null ? "" : token.trim();
    }

    @GetMapping("/apis/{slug}/resolve")
    public ResolvedApi resolve(@PathVariable String slug, @RequestParam(required = false) Integer version,
                               @RequestHeader(value = HEADER, required = false) String presented) {
        if (StringUtils.hasText(token) && !token.equals(presented)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal token.");
        }
        return service.resolve(slug, version);
    }
}
