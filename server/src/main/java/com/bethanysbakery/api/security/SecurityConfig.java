package com.bethanysbakery.api.security;

import com.bethanysbakery.api.auth.device.AdminDeviceService;
import com.bethanysbakery.api.auth.session.CookieService;
import com.bethanysbakery.api.auth.session.CsrfService;
import com.bethanysbakery.api.auth.session.JwtService;
import com.bethanysbakery.api.auth.session.RequestAuthenticationFilter;
import com.bethanysbakery.api.auth.session.SecurityProblemWriter;
import com.bethanysbakery.api.domain.auth.AdminUserRepository;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    private final JwtService jwtService;
    private final AdminDeviceService deviceService;
    private final CsrfService csrfService;
    private final CustomerRepository customers;
    private final AdminUserRepository admins;
    private final CookieService cookies;

    public SecurityConfig(JwtService jwtService, AdminDeviceService deviceService, CsrfService csrfService,
            CustomerRepository customers, AdminUserRepository admins, CookieService cookies) {
        this.jwtService = jwtService;
        this.deviceService = deviceService;
        this.csrfService = csrfService;
        this.customers = customers;
        this.admins = admins;
        this.cookies = cookies;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment env, ObjectMapper mapper) throws Exception {
        boolean local = env.acceptsProfiles(Profiles.of("local"));
        RequestAuthenticationFilter authenticationFilter = new RequestAuthenticationFilter(
            jwtService, deviceService, csrfService, customers, admins, cookies, mapper
        );
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
                .authenticationEntryPoint((req, res, e) -> SecurityProblemWriter.write(req, res, mapper,
                    HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required"))
                .accessDeniedHandler((req, res, e) -> SecurityProblemWriter.write(req, res, mapper,
                    HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied"))
            )
            .addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(HttpMethod.GET, "/api/v1/media/**", "/api/v1/menu-dates",
                    "/api/v1/menu-dates/**", "/api/v1/offerings", "/api/v1/offerings/**").permitAll();
                auth.requestMatchers("/api/v1/config", "/actuator/health/**", "/actuator/info", "/error",
                    "/api/v1/auth/csrf", "/api/v1/auth/otp/**", "/api/v1/auth/refresh",
                    "/admin/v1/devices/pair", "/admin/v1/auth/login", "/admin/v1/auth/refresh",
                    "/admin/v1/app-info").permitAll();
                auth.requestMatchers("/api/v1/me", "/api/v1/me/**", "/api/v1/orders",
                    "/api/v1/orders/**", "/api/v1/auth/logout").hasRole("CUSTOMER");
                auth.requestMatchers("/admin/v1/**").hasRole("ADMIN");
                if (local) {
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                }
                // Fail closed for the API namespaces: every implemented endpoint is authorized above.
                // Unmatched API paths get 401/403 instead of silently becoming public.
                auth.requestMatchers("/api/v1/**", "/admin/v1/**").denyAll();
                // Non-API paths reach the dispatcher so unknown routes keep the standard 404 problem.
                auth.anyRequest().permitAll();
            });
        return http.build();
    }

    @Bean
    CorsConfigurationSource denyAllCors() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", new CorsConfiguration());
        return source;
    }
}
