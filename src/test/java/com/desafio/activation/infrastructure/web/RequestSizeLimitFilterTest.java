package com.desafio.activation.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter();

    /** Simulates a chunked request: no Content-Length is known up front. */
    private static MockHttpServletRequest chunked(byte[] body) {
        var request = new MockHttpServletRequest("POST", "/api/v1/requests") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.addHeader("Transfer-Encoding", "chunked");
        request.setContent(body);
        return request;
    }

    @Test
    void rejectsChunkedBodyOverLimit() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(chunked(new byte[RequestSizeLimitFilter.MAX_BODY_BYTES + 1]), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void replaysChunkedBodyWithinLimit() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        byte[] body = "{\"externalId\":\"EXT-1\"}".getBytes(StandardCharsets.UTF_8);

        filter.doFilter(chunked(body), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(body);
        assertThat(chain.getRequest().getContentLengthLong()).isEqualTo(body.length);
    }

    @Test
    void rejectsDeclaredContentLengthOverLimit() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/requests");
        request.setContent(new byte[RequestSizeLimitFilter.MAX_BODY_BYTES + 1]);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
    }
}
