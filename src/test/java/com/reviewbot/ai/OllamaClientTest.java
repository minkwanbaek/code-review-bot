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

    @Test
    void analyzeConventions_ReturnsStructuredRulesFromOllamaResponse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://localhost:11434/api/generate"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"response":"{\\\"importRules\\\":[{\\\"order\\\":[\\\"java\\\",\\\"javax\\\",\\\"org\\\",\\\"com\\\"],\\\"forbiddenImports\\\":[\\\"java.util.*\\\"],\\\"message\\\":\\\"Use explicit imports\\\"}],\\\"namingRules\\\":{\\\"class\\\":\\\"PascalCase\\\",\\\"method\\\":\\\"camelCase\\\",\\\"variable\\\":\\\"camelCase\\\",\\\"constant\\\":\\\"UPPER_SNAKE_CASE\\\",\\\"package\\\":\\\"lowercase\\\"},\\\"forbiddenPatterns\\\":[{\\\"pattern\\\":\\\"System.out.println\\\",\\\"message\\\":\\\"Use logger\\\",\\\"severity\\\":\\\"WARNING\\\"}],\\\"archRules\\\":[{\\\"fromLayer\\\":\\\"controller\\\",\\\"toLayer\\\":\\\"repository\\\",\\\"allowed\\\":false,\\\"severity\\\":\\\"ERROR\\\",\\\"message\\\":\\\"No direct repository access\\\"}],\\\"dependencyRules\\\":[{\\\"layer\\\":\\\"controller\\\",\\\"forbiddenDependencies\\\":[\\\"repository\\\"],\\\"severity\\\":\\\"ERROR\\\",\\\"message\\\":\\\"Use service layer\\\"}]}"}
                        """, MediaType.APPLICATION_JSON));
        OllamaClient client = new OllamaClient("http://localhost:11434", "deepseek-coder:1.3b", 5, restTemplate);

        Conventions conventions = client.analyzeConventions("BZCC guide text");

        assertThat(conventions.getImportRules()).hasSize(1);
        assertThat(conventions.getImportRules().get(0).getOrder()).containsExactly("java", "javax", "org", "com");
        assertThat(conventions.getImportRules().get(0).getForbiddenImports()).containsExactly("java.util.*");
        assertThat(conventions.getImportOrder()).containsExactly("java", "javax", "org", "com");
        assertThat(conventions.getNamingRules()).containsEntry("class", "PascalCase");
        assertThat(conventions.getNamingPatterns()).containsEntry("method", "camelCase");
        assertThat(conventions.getForbiddenPatterns())
                .extracting(Conventions.ForbiddenPattern::getPattern)
                .containsExactly("System.out.println");
        assertThat(conventions.getForbiddenPatterns().get(0).getMessage()).isEqualTo("Use logger");
        assertThat(conventions.getArchRules()).hasSize(1);
        assertThat(conventions.getArchRules().get(0).isAllowed()).isFalse();
        assertThat(conventions.getDependencyRules()).hasSize(1);
        assertThat(conventions.getDependencyRules().get(0).getForbiddenDependencies()).containsExactly("repository");
        server.verify();
    }
}
