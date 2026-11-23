package com.example.apibuilder.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.apibuilder.runtime.BuilderClient.ResolvedApi;
import com.example.apibuilder.shared.ApiErrors;
import com.example.apibuilder.shared.QueryDefinition;
import com.example.apibuilder.shared.QueryDefinition.Direction;
import com.example.apibuilder.shared.QueryDefinition.Filter;
import com.example.apibuilder.shared.QueryDefinition.Operator;
import com.example.apibuilder.shared.QueryDefinition.Sort;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Exercises the runtime in isolation: the builder service is mocked, so these tests prove the runtime
 * compiles and executes the resolved definition correctly (parameters, injection safety, dates) and
 * propagates the builder's not-found result.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RuntimeServiceTests {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private BuilderClient builder;

    @Test
    void appliesCallerParametersToTheResolvedDefinition() {
        QueryDefinition definition = new QueryDefinition("ACCOUNTS", List.of("OWNER", "BALANCE"),
                List.of(new Filter("BALANCE", Operator.GT, null, "minimum", true),
                        new Filter("CITY", Operator.EQ, null, "city", false)),
                List.of(new Sort("BALANCE", Direction.DESC)), null);
        when(builder.resolve(eq("rich-accounts"), isNull()))
                .thenReturn(new ResolvedApi("rich-accounts", 1, definition));

        assertThat(mvc.get().uri("/api/rich-accounts?minimum=1000")).hasStatusOk().bodyJson()
                .extractingPath("$.data[*].OWNER").asArray()
                .containsExactly("Ishita Rao", "Meera Nair", "Asha Verma", "Sara Khan", "Dev Patel");
        assertThat(mvc.get().uri("/api/rich-accounts?minimum=1000&city=Delhi")).hasStatusOk().bodyJson()
                .extractingPath("$.data[*].OWNER").asArray().containsExactly("Asha Verma");

        assertThat(mvc.get().uri("/api/rich-accounts")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.detail").asString().contains("Missing required parameter 'minimum'");
        assertThat(mvc.get().uri("/api/rich-accounts?minimum=lots")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/rich-accounts?minimum=1&balance=5")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.detail").asString().contains("Unknown parameter 'balance'");
    }

    @Test
    void treatsCallerValuesAsDataAndPropagatesUnknownApis() {
        QueryDefinition definition = new QueryDefinition("ACCOUNTS", List.of("OWNER"),
                List.of(new Filter("OWNER", Operator.CONTAINS, null, "q", true)), List.of(), null);
        when(builder.resolve(eq("owner-search"), isNull()))
                .thenReturn(new ResolvedApi("owner-search", 1, definition));

        assertThat(mvc.get().uri("/api/owner-search").param("q", "SHA")).hasStatusOk().bodyJson()
                .extractingPath("$.data[*].OWNER").asArray().containsExactly("Asha Verma");
        assertThat(mvc.get().uri("/api/owner-search").param("q", "%")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.count", count -> assertThat(count).isEqualTo(0));
        assertThat(mvc.get().uri("/api/owner-search").param("q", "' OR '1'='1")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.count", count -> assertThat(count).isEqualTo(0));

        when(builder.resolve(eq("does-not-exist"), any()))
                .thenThrow(ApiErrors.notFound("No API named 'does-not-exist' has been published."));
        assertThat(mvc.get().uri("/api/does-not-exist")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void returnsDatesAndTimestampsExactlyAsStored() {
        QueryDefinition definition = new QueryDefinition("TRANSACTIONS", List.of("CREATED_AT"),
                List.of(new Filter("CREATED_AT", Operator.LT, null, "before", true)), List.of(), null);
        when(builder.resolve(eq("first-deposit"), isNull()))
                .thenReturn(new ResolvedApi("first-deposit", 1, definition));

        assertThat(mvc.get().uri("/api/first-deposit?before=2026-01-10T00:00")).hasStatusOk().bodyJson()
                .isLenientlyEqualTo("""
                        {"data": [{"CREATED_AT": "2026-01-05T09:30:00"}]}""");
    }
}
