package com.example.apibuilder.builder;

import com.example.apibuilder.builder.ApiBuilderService.ResolvedApi;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints. The runtime service calls these to fetch the stored definition behind a
 * published API; they are not routed through the public gateway.
 */
@RestController
@RequestMapping("/internal")
public class InternalApiController {

    private final ApiBuilderService service;

    public InternalApiController(ApiBuilderService service) {
        this.service = service;
    }

    @GetMapping("/apis/{slug}/resolve")
    public ResolvedApi resolve(@PathVariable String slug, @RequestParam(required = false) Integer version) {
        return service.resolve(slug, version);
    }
}
