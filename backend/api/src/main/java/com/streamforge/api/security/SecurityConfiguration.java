package com.streamforge.api.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyAuthenticationFilter apiKeyFilter) throws Exception {
        // Anonymous authentication has no authority. It stays enabled so Spring Security
        // reports a missing credential as 401 instead of treating denyAll as 403.
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/devices").hasAuthority(ApiKeyAuthenticationFilter.DEVICE_ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/devices", "/api/v1/devices/**").hasAuthority(ApiKeyAuthenticationFilter.DEVICE_ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/analytics/**").hasAuthority(ApiKeyAuthenticationFilter.DEVICE_ADMIN)
                        .requestMatchers("/api/v1/telemetry/**").hasAuthority(ApiKeyAuthenticationFilter.INGESTION)
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> SecurityProblems.write(
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "Unauthorized",
                                "Authentication is required."))
                        .accessDeniedHandler((request, response, accessDeniedException) -> SecurityProblems.write(
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                "Forbidden",
                                "The credential is not authorized for this operation.")))
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
