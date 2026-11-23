package com.example.apibuilder.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The control plane is admin-only: the web page and every {@code /builder/**} endpoint need the login,
 * while the service-to-service {@code /internal/**} path stays open for the runtime.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BuilderSecurityTests {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void managementEndpointsRequireTheAdminLogin() {
        assertThat(mvc.get().uri("/builder/apis")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/builder/schema")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theAdminCanSignInWithBasicAuth() {
        assertThat(mvc.get().uri("/builder/apis").with(httpBasic("admin", "admin"))).hasStatusOk();
        assertThat(mvc.get().uri("/builder/apis").with(httpBasic("admin", "wrong")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theInternalResolveEndpointIsReachableWithoutTheAdminLogin() {
        // No admin credentials: permitAll lets the runtime through; the API simply does not exist yet.
        assertThat(mvc.get().uri("/internal/apis/anything/resolve")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
