package com.manara.backend.email.template;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders classpath HTML templates by substituting {@code {{PLACEHOLDER}}} tokens.
 *
 * <p>Deliberately minimal — no template engine is added for what is currently a handful of
 * transactional emails, while richer templates remain possible later by swapping this out behind
 * the same call.
 *
 * <p>Two safety properties matter here:
 * <ul>
 *   <li>every substituted value is HTML-escaped, so template data can never inject markup;</li>
 *   <li>substitution is single-pass, so a value that itself looks like a placeholder is emitted
 *       literally instead of being expanded.</li>
 * </ul>
 * A placeholder with no supplied value is an error rather than a silently empty gap.
 */
@Component
public class EmailTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public String render(String templatePath, Map<String, String> variables) {
        String template = templateCache.computeIfAbsent(templatePath, EmailTemplateRenderer::load);

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.get(name);
            if (value == null) {
                throw new IllegalStateException(
                        "No value supplied for placeholder {{" + name + "}} in template " + templatePath);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(HtmlUtils.htmlEscape(value)));
        }
        matcher.appendTail(rendered);

        return rendered.toString();
    }

    private static String load(String templatePath) {
        try (InputStream stream = new ClassPathResource(templatePath).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Email template not found on the classpath: " + templatePath, ex);
        }
    }
}
