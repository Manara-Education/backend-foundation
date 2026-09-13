package com.manara.backend.session.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@EnableRedisHttpSession(redisNamespace = SessionConfig.REDIS_NAMESPACE)
@EnableConfigurationProperties(SessionCeilingProperties.class)
public class SessionConfig {

    /**
     * The prefix of every session key in Redis. Named here rather than left to the annotation's
     * default because {@code SessionCeiling} reads and deletes session keys directly, and has to agree
     * with the repository about where they are.
     *
     * <p>It is the namespace sessions have always been stored under, kept so that nobody is signed out
     * by naming it. {@code spring.session.redis.namespace} in application.properties does not set it:
     * that property is read by Spring Boot's session auto-configuration, which this build does not
     * include.
     */
    public static final String REDIS_NAMESPACE = RedisSessionRepository.DEFAULT_KEY_NAMESPACE;

    @Value("${app.security.cookie-secure:false}")
    private boolean cookieSecure;

    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer s = new DefaultCookieSerializer();
        s.setCookieName("MANARA_SESSION");
        s.setUseHttpOnlyCookie(true);
        s.setUseSecureCookie(cookieSecure);
        s.setSameSite("Lax");
        s.setCookiePath("/");
        return s;
    }
}
