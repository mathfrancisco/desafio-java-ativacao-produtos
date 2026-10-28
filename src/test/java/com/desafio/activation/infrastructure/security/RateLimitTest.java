package com.desafio.activation.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.rate-limit.burst=2", "app.rate-limit.requests-per-minute=1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitTest {

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor reader(String clientId) {
        return jwt().jwt(token -> token.subject(clientId))
                .authorities(new SimpleGrantedAuthority("SCOPE_activation:read"));
    }

    @Test
    void rejectsClientOverLimitWithRetryAfterAndKeepsOtherClientsServed() throws Exception {
        mvc.perform(get("/api/v1/requests").with(reader("limited"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/requests").with(reader("limited"))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/requests").with(reader("limited")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("/problems/too-many-requests"));

        mvc.perform(get("/api/v1/requests").with(reader("other"))).andExpect(status().isOk());
    }
}
