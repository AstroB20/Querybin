package com.example.apibuilder.runtime;

import java.util.Map;

import com.example.apibuilder.runtime.RuntimeService.QueryResult;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The endpoints that users create: every published API is served here. */
@RestController
@RequestMapping("/api")
public class PublishedApiController {

    private final RuntimeService service;

    public PublishedApiController(RuntimeService service) {
        this.service = service;
    }

    @GetMapping("/{slug}")
    public QueryResult current(@PathVariable String slug, @RequestParam Map<String, String> params) {
        return service.run(slug, null, params);
    }

    /** Time machine: every version that was ever published stays callable. */
    @GetMapping("/{slug}/v/{version}")
    public QueryResult version(@PathVariable String slug, @PathVariable int version,
                               @RequestParam Map<String, String> params) {
        return service.run(slug, version, params);
    }
}
