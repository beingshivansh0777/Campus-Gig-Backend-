package org.riteshingle.campusgig.Configuration;

import lombok.RequiredArgsConstructor;
import org.riteshingle.campusgig.Security.JwtRequestFilter;
import org.riteshingle.campusgig.Security.RateLimitFilter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
@EnableWebSecurity
public class Config {

    private final JwtRequestFilter jwtRequestFilter;
    private final RateLimitFilter rateLimitFilter;


    // =========================================================
    // SECURITY FILTER CHAIN
    // =========================================================

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        return http

                // Disable CSRF for REST API
                .csrf(AbstractHttpConfigurer::disable)

                // Enable CORS
                .cors(cors ->
                        cors.configurationSource(corsConfigurationSource())
                )

                // Authorization rules
                .authorizeHttpRequests(req -> req

                        // CORS preflight requests
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Public test endpoint
                        .requestMatchers("/auth/test").permitAll()

                        // WebSocket endpoints
                        .requestMatchers("/ws/**").permitAll()

                        // Admin authentication
                        .requestMatchers("/api/admin/login").permitAll()
                        .requestMatchers("/api/admin/register").permitAll()

                        // User authentication
                        .requestMatchers("/api/auth/**").permitAll()

                        // Swagger / OpenAPI
                        .requestMatchers(
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**"
                        ).permitAll()

                                // Serve uploaded profile images publicly
                                .requestMatchers("/uploads/**").permitAll()

                           // Everything else requires authentication
                        .anyRequest().authenticated()
                )

                // Stateless authentication
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                // JWT filter
                .addFilterBefore(
                        jwtRequestFilter,
                        UsernamePasswordAuthenticationFilter.class
                )

                .build();
    }


    // =========================================================
    // PASSWORD ENCODER
    // =========================================================

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }


    // =========================================================
    // AUTHENTICATION MANAGER
    // =========================================================

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {

        return configuration.getAuthenticationManager();
    }


    // =========================================================
    // CORS CONFIGURATION
    // =========================================================

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration =
                new CorsConfiguration();

        // Frontend URLs
        configuration.setAllowedOriginPatterns(
                List.of(
                        "http://localhost:5173",
                        "http://127.0.0.1:5173"
                )
        );

        // HTTP methods
        configuration.setAllowedMethods(
                List.of(
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                )
        );

        // Request headers
        configuration.setAllowedHeaders(
                List.of("*")
        );

        // Authorization / credentials
        configuration.setAllowCredentials(true);

        // Apply globally
        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }


    // =========================================================
    // GLOBAL CORS FILTER
    // =========================================================

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>();

        registration.setFilter(
                new CorsFilter(corsConfigurationSource())
        );

        registration.addUrlPatterns("/*");

        // CORS runs before everything else
        registration.setOrder(
                Ordered.HIGHEST_PRECEDENCE
        );

        return registration;
    }


    // =========================================================
    // GLOBAL RATE LIMIT FILTER
    // =========================================================

    @Bean
    public FilterRegistrationBean<RateLimitFilter>
    rateLimitFilterRegistration() {

        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>();

        registration.setFilter(rateLimitFilter);

        registration.addUrlPatterns("/*");

        // Run after CORS
        registration.setOrder(
                Ordered.HIGHEST_PRECEDENCE + 1
        );

        return registration;
    }
}