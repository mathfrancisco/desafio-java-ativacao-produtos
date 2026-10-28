package com.desafio.activation.api;

import com.desafio.activation.domain.ActivationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivationRequestApiTest {

    private static final String VALID_BODY = """
            {"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ActivationRequestRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    private static RequestPostProcessor client(String clientId, String... scopes) {
        var authorities = Arrays.stream(scopes)
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();
        return jwt().jwt(token -> token.subject(clientId)).authorities(authorities);
    }

    private static RequestPostProcessor writer() {
        return client("client-a", "activation:write", "activation:read");
    }

    private String createValid() throws Exception {
        var location = mvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    @Test
    void createsRequestWithLocationAndPendingStatus() throws Exception {
        mvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/requests/")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.externalId").value("EXT-12345"))
                .andExpect(jsonPath("$.customerId").value("CUST-10001"))
                .andExpect(jsonPath("$.productCode").value("INTERNET-500"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt", endsWith("Z")))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidFieldsWithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"","customerId":"CUST 1","productCode":"%s"}""".formatted("X".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/validation"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("externalId")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("customerId")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("productCode")))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        assertThat(repository.count()).isZero();
    }

    @Test
    void rejectsMissingFields() throws Exception {
        mvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));
    }

    @Test
    void rejectsMalformedJsonWithoutInternalDetails() throws Exception {
        mvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"externalId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed JSON request body"))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void identicalRetryReturnsExistingRequest() throws Exception {
        var id = createValid();

        mvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameExternalIdWithDifferentContentIsConflict() throws Exception {
        createValid();

        mvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"TV-100"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/duplicate-external-id"));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameExternalIdFromAnotherClientIsConflict() throws Exception {
        createValid();

        mvc.perform(post("/api/v1/requests").with(client("client-b", "activation:write"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void getsExistingRequest() throws Exception {
        var id = createValid();

        mvc.perform(get("/api/v1/requests/{id}", id).with(client("client-a", "activation:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void unknownRequestIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(client("client-a", "activation:read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));
    }

    @Test
    void requestOfAnotherClientIsNotFound() throws Exception {
        var id = createValid();

        mvc.perform(get("/api/v1/requests/{id}", id).with(client("client-b", "activation:read")))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/requests/not-a-uuid").with(client("client-a", "activation:read")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("id"));
    }

    @Test
    void listsOnlyOwnRequestsWithFilters() throws Exception {
        createValid();
        mvc.perform(post("/api/v1/requests").with(client("client-b", "activation:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalId":"EXT-B","customerId":"CUST-10001","productCode":"INTERNET-500"}"""));

        mvc.perform(get("/api/v1/requests").param("status", "PENDING").param("customerId", "CUST-10001")
                        .with(client("client-a", "activation:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].externalId").value("EXT-12345"));

        mvc.perform(get("/api/v1/requests").param("status", "COMPLETED")
                        .with(client("client-a", "activation:read")))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void rejectsInvalidPagingAndStatus() throws Exception {
        mvc.perform(get("/api/v1/requests").param("size", "500").with(client("client-a", "activation:read")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/requests").param("status", "DONE").with(client("client-a", "activation:read")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingScopeIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/requests").with(client("client-a", "activation:read"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(client("client-a", "activation:write")))
                .andExpect(status().isForbidden());
    }

    @Test
    void echoesSafeCorrelationId() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())
                        .header("X-Correlation-Id", "abc-123")
                        .with(client("client-a", "activation:read")))
                .andExpect(header().string("X-Correlation-Id", "abc-123"))
                .andExpect(jsonPath("$.correlationId").value("abc-123"));
    }

    @Test
    void rejectsOversizedBody() throws Exception {
        mvc.perform(post("/api/v1/requests").with(writer())
                        .contentType(MediaType.APPLICATION_JSON).content("x".repeat(17 * 1024)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
