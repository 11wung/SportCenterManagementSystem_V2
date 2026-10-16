package com.swp391.sportcentermanagementsystem_v2.config;

import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.security.JwtAuthenticationFilter;
import com.swp391.sportcentermanagementsystem_v2.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import jakarta.servlet.DispatcherType;

import java.util.List;

@Configuration
public class SecurityConfig {

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http,
                        JwtService jwtService,
                        UserRepository userRepository) throws Exception {
                JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, userRepository);

                http.authorizeHttpRequests(auth -> auth

                                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                                .requestMatchers(HttpMethod.POST, "/api/auth/register",
                                                "/api/auth/login")
                                .permitAll()
                                // Allow guests to view available membership packages
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/packages",
                                                "/api/packages/{id}")
                                .permitAll()
                                // PayOS Redirect & Webhook
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/payments/payos/return",
                                                "/api/payments/payos/cancel")
                                .permitAll()

                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/payments/payos/webhook")
                                .permitAll()
                                // MANAGER
                                .requestMatchers(HttpMethod.GET, "/api/test/manager",
                                                "/api/manager/accounts",
                                                "/api/manager/accounts/search",
                                                "/api/manager/accounts/{id}",
                                                "/api/manager/permissions")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.POST,
                                                "/api/manager/accounts",
                                                "/api/manager/packages")
                                .hasRole("MANAGER")
                                .requestMatchers(
                                                HttpMethod.PATCH,
                                                "/api/manager/accounts/*/deactivate",
                                                "/api/manager/accounts/*/active",
                                                "/api/manager/accounts/*/staff-profile",
                                                "/api/manager/accounts/{id}",
                                                "/api/manager/packages/{id}",
                                                "/api/manager/packages/{id}/status")
                                .hasRole("MANAGER")
                                // MANAGER

                                // MEMBER
                                .requestMatchers(
                                                HttpMethod.PATCH,
                                                "/api/users/me/member-profile")
                                .hasRole("MEMBER")
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/member/subscriptions/me",
                                                "/api/member/subscriptions/me/status")
                                .hasRole("MEMBER")
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/member/subscriptions",
                                                "/api/member/subscriptions/renew",
                                                "/api/member/subscriptions/upgrade",
                                                "/api/member/subscriptions/{id}/online-payment",
                                                "/api/member/subscriptions/{id}/online-payment/check")
                                .hasRole("MEMBER")

                                .requestMatchers(
                                                HttpMethod.PATCH,
                                                "/api/member/subscriptions/{id}/cancel")
                                .hasRole("MEMBER")
                                // MEMBER

                                // COACH
                                .requestMatchers(
                                                HttpMethod.PATCH,
                                                "/api/users/me/coach-profile")
                                .hasRole("COACH")
                                // COACH

                                // RECEPTIONIST
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/receptionist/members",
                                                "/api/receptionist/members/*")
                                .hasRole("RECEPTIONIST")
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/receptionist/members")
                                .hasRole("RECEPTIONIST")
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/receptionist/members/*/subscriptions")
                                .hasRole("RECEPTIONIST")
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/receptionist/members/*/subscriptions",
                                                "/api/receptionist/members/*/subscriptions/renew",
                                                "/api/receptionist/members/*/subscriptions/upgrade")
                                .hasRole("RECEPTIONIST")
                                // Chỉ Receptionist được xác nhận thu tiền mặt.
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/receptionist/payments/cash")
                                .hasRole("RECEPTIONIST")
                                // RECEPTIONIST
                                .anyRequest().authenticated());

                http.csrf(csrf -> csrf.disable());

                http.cors(Customizer.withDefaults());

                http.sessionManagement(session -> session
                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

                http.formLogin(form -> form.disable());
                http.httpBasic(basic -> basic.disable());

                http.addFilterBefore(jwtFilter,
                                UsernamePasswordAuthenticationFilter.class);

                http.exceptionHandling(ex -> ex
                                .authenticationEntryPoint((request, response, authException) -> response
                                                .sendError(HttpStatus.UNAUTHORIZED.value())));

                return http.build();
        }

        // CORS: chỉ các địa chỉ FE đã khai trong app.cors.allowed-origins được gọi API.
        @Bean
        public CorsConfigurationSource corsConfigurationSource(
                        @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {

                CorsConfiguration config = new CorsConfiguration();
                config.setAllowedOrigins(allowedOrigins);
                config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
                config.setMaxAge(3600L);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/api/**", config);
                return source;
        }

}
