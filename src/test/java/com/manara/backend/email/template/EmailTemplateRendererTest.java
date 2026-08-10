package com.manara.backend.email.template;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplateRendererTest {

    private static final String TEMPLATE = "templates/email/renderer-test.html";

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void substitutesPlaceholders() {
        String html = renderer.render(TEMPLATE, Map.of("NAME", "Manara", "NOTE", "hello"));

        assertThat(html).contains("Manara").contains("hello").doesNotContain("{{");
    }

    @Test
    void escapesSubstitutedValues() {
        String html = renderer.render(TEMPLATE,
                Map.of("NAME", "<script>alert(1)</script>", "NOTE", "a & b"));

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;").contains("a &amp; b");
    }

    /** A value that looks like a placeholder must be emitted literally, not expanded. */
    @Test
    void doesNotExpandPlaceholdersFoundInsideValues() {
        String html = renderer.render(TEMPLATE, Map.of("NAME", "{{NOTE}}", "NOTE", "secret"));

        assertThat(html).contains("<h1>{{NOTE}}</h1>");
    }

    @Test
    void failsWhenAPlaceholderHasNoValue() {
        assertThatThrownBy(() -> renderer.render(TEMPLATE, Map.of("NAME", "Manara")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOTE");
    }

    @Test
    void failsWhenTheTemplateIsMissing() {
        assertThatThrownBy(() -> renderer.render("templates/email/does-not-exist.html", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist.html");
    }
}
