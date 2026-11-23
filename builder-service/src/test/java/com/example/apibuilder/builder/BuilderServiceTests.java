package com.example.apibuilder.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class BuilderServiceTests {

    @Autowired
    private MockMvcTester mvc;

    private static final RequestPostProcessor ADMIN = httpBasic("admin", "admin");

    @Test
    void previewRunsTheDefinitionAndReturnsDatesExactlyAsStored() {
        assertThat(preview("""
                {"definition": {"table": "TRANSACTIONS", "columns": ["CREATED_AT"],
                 "filters": [{"column": "CREATED_AT", "operator": "LT", "value": "2026-01-10T00:00"}]}}"""))
                .hasStatusOk().bodyJson()
                .isLenientlyEqualTo("""
                        {"rows": [{"CREATED_AT": "2026-01-05T09:30:00"}]}""");

        assertThat(preview("""
                {"definition": {"table": "ACCOUNTS", "columns": ["OPENED_ON"],
                 "filters": [{"column": "OPENED_ON", "operator": "LT", "value": "2018-01-01"}]}}"""))
                .hasStatusOk().bodyJson()
                .isLenientlyEqualTo("""
                        {"rows": [{"OPENED_ON": "2017-12-12"}]}""");
    }

    @Test
    void previewOnlyReachesKnownTablesAndTreatsValuesAsData() {
        assertThat(preview("""
                {"definition": {"table": "ACCOUNTS\\"; DROP TABLE ACCOUNTS; --", "columns": ["ID"]}}"""))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(preview("""
                {"definition": {"table": "ACCOUNTS", "columns": ["ID FROM ACCOUNTS; --"]}}"""))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(preview("""
                {"definition": {"table": "API_VERSION", "columns": ["SLUG"]}}"""))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(preview("""
                {"definition": {"table": "BUILDER.API_VERSION", "columns": ["SLUG"]}}"""))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void timeMachineKeepsEveryVersionResolvableAndRollsBackAsNewVersion() {
        create("owners", """
                {"table": "ACCOUNTS", "columns": ["OWNER"], "sort": [{"column": "ID"}]}""");
        String second = """
                {"description": "Owners with cities",
                 "definition": {"table": "ACCOUNTS", "columns": ["OWNER", "CITY"], "sort": [{"column": "ID"}], "limit": 3}}""";
        assertThat(put("/builder/apis/owners", second)).hasStatusOk();
        assertThat(put("/builder/apis/owners", second)).hasStatus(HttpStatus.CONFLICT);

        assertThat(mvc.get().with(ADMIN).uri("/internal/apis/owners/resolve")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("""
                        {"version": 2, "definition": {"columns": ["OWNER", "CITY"], "limit": 3}}""");
        assertThat(mvc.get().with(ADMIN).uri("/internal/apis/owners/resolve?version=1")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("""
                        {"version": 1, "definition": {"columns": ["OWNER"], "limit": 100}}""");

        assertThat(mvc.get().with(ADMIN).uri("/builder/apis/owners/diff?from=1&to=2")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("""
                        {"changes": [
                          {"field": "description", "kind": "CHANGED", "before": null, "after": "Owners with cities"},
                          {"field": "column", "kind": "ADDED", "after": "CITY"},
                          {"field": "limit", "kind": "CHANGED", "before": "100", "after": "3"}]}""");

        assertThat(mvc.post().with(ADMIN).uri("/builder/apis/owners/versions/1/rollback")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("""
                        {"version": 3, "currentVersion": 3, "description": null,
                         "definition": {"columns": ["OWNER"], "limit": 100}}""");
        assertThat(mvc.get().with(ADMIN).uri("/builder/apis/owners/versions")).hasStatusOk().bodyJson()
                .extractingPath("$[*].version").asArray().containsExactly(3, 2, 1);

        assertThat(mvc.get().with(ADMIN).uri("/builder/apis/does-not-exist")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().with(ADMIN).uri("/internal/apis/does-not-exist/resolve")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void curlBuilderEncodesParametersAndTargetsTheChosenVersionAndShell() {
        create("curl-demo", """
                {"table": "ACCOUNTS", "columns": ["OWNER"],
                 "filters": [{"column": "BALANCE", "operator": "GTE", "param": "minimum"},
                             {"column": "CITY", "operator": "EQ", "param": "city", "required": false}]}""");

        assertThat(post("/builder/apis/curl-demo/curl", """
                {"version": 1, "shell": "POWERSHELL", "params": {"minimum": "1000", "city": "New Delhi & 'Co'"}}"""))
                .hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        {"command": "curl.exe -H 'Accept: application/json' 'http://localhost/api/curl-demo/v/1?minimum=1000&city=New%20Delhi%20%26%20%27Co%27'",
                         "warnings": []}""");
        assertThat(post("/builder/apis/curl-demo/curl", "{}")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"command": "curl -H 'Accept: application/json' 'http://localhost/api/curl-demo'",
                 "warnings": ["Required parameter 'minimum' has no value yet."]}""");
        assertThat(post("/builder/apis/curl-demo/curl", """
                {"params": {"nope": "1"}}""")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private void create(String slug, String definition) {
        assertThat(post("/builder/apis", "{\"slug\": \"" + slug + "\", \"definition\": " + definition + "}"))
                .hasStatus(HttpStatus.CREATED);
    }

    private MvcTestResult preview(String body) {
        return post("/builder/preview", body);
    }

    private MvcTestResult post(String uri, String body) {
        return mvc.post().with(ADMIN).uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult put(String uri, String body) {
        return mvc.put().with(ADMIN).uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }
}
