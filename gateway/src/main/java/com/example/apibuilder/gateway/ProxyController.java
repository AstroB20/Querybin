package com.example.apibuilder.gateway;

import java.net.URI;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * A small reverse proxy. {@code /builder/**} is forwarded to the builder service and {@code /api/**} to the
 * runtime service. Upstream status codes and bodies (including RFC 7807 errors) are passed straight through.
 */
@RestController
public class ProxyController {

    /** Hop-by-hop and host-specific headers that must not be forwarded. */
    private static final Set<String> SKIP = Set.of("host", "content-length", "connection",
            "keep-alive", "transfer-encoding", "accept-encoding", "te", "trailer", "upgrade");

    private final RestClient client = RestClient.create();
    private final String builderUrl;
    private final String runtimeUrl;

    public ProxyController(@Value("${gateway.builder-url:http://127.0.0.1:8081}") String builderUrl,
                          @Value("${gateway.runtime-url:http://127.0.0.1:8082}") String runtimeUrl) {
        this.builderUrl = trimTrailingSlash(builderUrl);
        this.runtimeUrl = trimTrailingSlash(runtimeUrl);
    }

    @RequestMapping("/builder/**")
    public ResponseEntity<byte[]> builder(HttpServletRequest request,
                                         @RequestBody(required = false) byte[] body) {
        return forward(builderUrl, request, body);
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> api(HttpServletRequest request,
                                     @RequestBody(required = false) byte[] body) {
        return forward(runtimeUrl, request, body);
    }

    private ResponseEntity<byte[]> forward(String base, HttpServletRequest request, byte[] body) {
        String query = request.getQueryString();
        URI target = URI.create(base + request.getRequestURI() + (query != null ? "?" + query : ""));
        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        RestClient.RequestBodySpec spec = client.method(method).uri(target);
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (!SKIP.contains(name.toLowerCase())) {
                spec = spec.header(name, java.util.Collections.list(request.getHeaders(name)).toArray(String[]::new));
            }
        }
        if (body != null && body.length > 0) {
            spec = spec.body(body);
        }

        return spec.exchange((req, response) -> {
            byte[] payload = response.getBody() != null
                    ? StreamUtils.copyToByteArray(response.getBody()) : new byte[0];
            HttpHeaders out = new HttpHeaders();
            MediaType contentType = response.getHeaders().getContentType();
            if (contentType != null) {
                out.setContentType(contentType);
            }
            List<String> cacheControl = response.getHeaders().get(HttpHeaders.CACHE_CONTROL);
            if (cacheControl != null) {
                out.put(HttpHeaders.CACHE_CONTROL, cacheControl);
            }
            return ResponseEntity.status(response.getStatusCode()).headers(out).body(payload);
        });
    }

    private static String trimTrailingSlash(String url) {
        return url.replaceAll("/+$", "");
    }
}
