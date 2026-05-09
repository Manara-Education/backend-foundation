package com.manara.backend.swagger.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Optional;

/**
 * OpenAPI document for the Manara backend. The UI is served at {@code /swagger-ui.html}
 * and the raw spec at {@code /v3/api-docs}. Auth is session-cookie based, so the documented
 * security scheme is the {@code MANARA_SESSION} cookie issued after login plus the
 * {@code X-XSRF-TOKEN} header required by CSRF-protected (state-changing) requests.
 */
@Configuration
public class OpenApiConfig {

    private static final String SESSION_COOKIE_SCHEME = "sessionCookie";
    private static final String CSRF_HEADER_SCHEME = "csrfToken";

    private static final String API_TITLE = "Manara Backend API";
    private static final String API_DESCRIPTION = "Edu Mobile Application Backend — REST API documentation.";
    private static final String API_VERSION = "v1";
    private static final String CONTACT_NAME = "Manara";
    private static final String CONTACT_EMAIL = "support@manara.app";
    private static final String LICENSE_NAME = "Proprietary";

    private static final String SESSION_COOKIE_NAME = "MANARA_SESSION";
    private static final String SESSION_COOKIE_DESCRIPTION = "Session cookie issued by /api/v1/auth/login.";

    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";
    private static final String CSRF_HEADER_DESCRIPTION =
            "CSRF token echoed from the XSRF-TOKEN cookie; required on state-changing requests.";

    private static final String CONTROLLER_TAG_SUFFIX = "-controller";

    @Bean
    public Info apiInfo() {
        return new Info()
                .title(API_TITLE)
                .description(API_DESCRIPTION)
                .version(API_VERSION)
                .contact(new Contact().name(CONTACT_NAME).email(CONTACT_EMAIL))
                .license(new License().name(LICENSE_NAME));
    }

    @Bean
    public SecurityScheme sessionCookieScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name(SESSION_COOKIE_NAME)
                .description(SESSION_COOKIE_DESCRIPTION);
    }

    @Bean
    public SecurityScheme csrfHeaderScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(CSRF_HEADER_NAME)
                .description(CSRF_HEADER_DESCRIPTION);
    }

    @Bean
    public Components apiComponents(SecurityScheme sessionCookieScheme, SecurityScheme csrfHeaderScheme) {
        return new Components()
                .addSecuritySchemes(SESSION_COOKIE_SCHEME, sessionCookieScheme)
                .addSecuritySchemes(CSRF_HEADER_SCHEME, csrfHeaderScheme);
    }

    @Bean
    public SecurityRequirement apiSecurityRequirement() {
        return new SecurityRequirement()
                .addList(SESSION_COOKIE_SCHEME)
                .addList(CSRF_HEADER_SCHEME);
    }

    @Bean
    public OpenAPI manaraOpenAPI(Info apiInfo, Components apiComponents, SecurityRequirement apiSecurityRequirement) {
        return new OpenAPI()
                .info(apiInfo)
                .components(apiComponents)
                .addSecurityItem(apiSecurityRequirement);
    }

    @Bean
    public OpenApiCustomizer featureTagCustomizer() {
        return openApi -> {
            Optional.ofNullable(openApi.getTags())
                    .ifPresent(tags -> tags.forEach(tag -> tag.setName(stripControllerSuffix(tag.getName()))));
            Optional.ofNullable(openApi.getPaths())
                    .ifPresent(paths -> paths.values().stream()
                            .flatMap(pathItem -> pathItem.readOperations().stream())
                            .filter(operation -> operation.getTags() != null)
                            .forEach(operation -> operation.setTags(
                                    operation.getTags().stream().map(OpenApiConfig::stripControllerSuffix).toList())));
        };
    }

    private static String stripControllerSuffix(String tag) {
        return tag != null && tag.endsWith(CONTROLLER_TAG_SUFFIX)
                ? tag.substring(0, tag.length() - CONTROLLER_TAG_SUFFIX.length())
                : tag;
    }
}
