package com.example.apibuilder.builder;

import static com.example.apibuilder.shared.ApiErrors.badRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.apibuilder.shared.QueryCompiler.ApiParameter;

import org.springframework.web.util.UriUtils;

/** Builds a copy-and-paste curl command for calling a published API. */
public final class CurlBuilder {

    public enum Shell {
        BASH("curl", "'\\''"),
        /** Windows PowerShell aliases {@code curl} to Invoke-WebRequest, so the real binary is named explicitly. */
        POWERSHELL("curl.exe", "''");

        private final String program;
        private final String escapedQuote;

        Shell(String program, String escapedQuote) {
            this.program = program;
            this.escapedQuote = escapedQuote;
        }

        String quote(String text) {
            return "'" + text.replace("'", escapedQuote) + "'";
        }
    }

    public record CurlCommand(String command, String url, List<String> warnings) {
    }

    private CurlBuilder() {
    }

    static CurlCommand build(String baseUrl, String slug, Integer version, List<ApiParameter> declared,
                             Map<String, String> values, Shell shell) {
        Map<String, String> supplied = values == null ? Map.of() : values;
        List<String> names = declared.stream().map(ApiParameter::name).toList();
        for (String name : supplied.keySet()) {
            if (!names.contains(name)) {
                throw badRequest("Unknown parameter '" + name + "'.");
            }
        }

        StringBuilder url = new StringBuilder(baseUrl).append("/api/").append(slug);
        if (version != null) {
            url.append("/v/").append(version);
        }
        List<String> warnings = new ArrayList<>();
        char separator = '?';
        for (ApiParameter parameter : declared) {
            String value = supplied.get(parameter.name());
            if (value == null || value.isBlank()) {
                if (parameter.required()) {
                    warnings.add("Required parameter '" + parameter.name() + "' has no value yet.");
                }
                continue;
            }
            url.append(separator).append(parameter.name()).append('=')
                    .append(UriUtils.encode(value, StandardCharsets.UTF_8));
            separator = '&';
        }

        Shell target = shell == null ? Shell.BASH : shell;
        String command = target.program + " -H " + target.quote("Accept: application/json") + " "
                + target.quote(url.toString());
        return new CurlCommand(command, url.toString(), warnings);
    }
}
