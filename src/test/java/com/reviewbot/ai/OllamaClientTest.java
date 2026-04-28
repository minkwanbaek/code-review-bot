package com.reviewbot.ai;

import com.reviewbot.convention.Conventions;
import com.reviewbot.review.Severity;
import com.reviewbot.review.Violation;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class OllamaClientTest {

    @Test
    void reviewCode_ReturnsViolationsFromOllamaResponse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://localhost:11434/api/generate"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"response":"[{\\"severity\\":\\"WARNING\\",\\"rule\\":\\"Logging\\",\\"message\\":\\"Use logger\\",\\"lineNumber\\":12}]"}
                        """, MediaType.APPLICATION_JSON));
        OllamaClient client = new OllamaClient("http://localhost:11434", "test-model", 5, restTemplate);

        List<Violation> violations = client.reviewCode("+ System.out.println(value);", new Conventions());

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getSeverity()).isEqualTo(Severity.WARNING);
        assertThat(violations.get(0).getRule()).isEqualTo("Logging");
        assertThat(violations.get(0).getLineNumber()).isEqualTo(12);
        server.verify();
    }
}
