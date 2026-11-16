package com.bethanysbakery.api.security;

import com.bethanysbakery.api.common.error.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment env, ObjectMapper mapper) throws Exception {
        boolean local = env.acceptsProfiles(Profiles.of("local"));
        http
            .csrf(csrf -> csrf.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(cors -> cors.configurationSource(denyAllCors()))
            .headers(headers -> headers
                .contentTypeOptions(cto -> {})
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> writeNotFound(req, res, mapper))
                .accessDeniedHandler((req, res, e) -> writeNotFound(req, res, mapper))
            )
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/api/v1/config", "/actuator/health/**", "/actuator/info", "/error").permitAll();
                if (local) {
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                }
                auth.anyRequest().denyAll();
            });
        return http.build();
    }

    @Bean
    CorsConfigurationSource denyAllCors() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", new CorsConfiguration());
        return source;
    }

    private void writeNotFound(HttpServletRequest request, HttpServletResponse response, ObjectMapper mapper)
            throws IOException {
        String requestId = MDC.get("requestId");
        Problem body = new Problem(
            "about:blank",
            "Not found",
            HttpStatus.NOT_FOUND.value(),
            "Resource not found",
            request.getRequestURI(),
            "NOT_FOUND",
            requestId,
            null
        );
        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), body);
    }
}
