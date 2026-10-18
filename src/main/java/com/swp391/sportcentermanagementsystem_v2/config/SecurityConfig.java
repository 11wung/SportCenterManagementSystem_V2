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

import io.swagger.v3.oas.models.PathItem;

@Configuration
public class SecurityConfig {

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http,
                        JwtService jwtService,
                        UserRepository userRepository) throws Exception {
                JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, userRepository);

                http.authorizeHttpRequests(auth -> auth

                                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                                // SWAGGER
                                .requestMatchers(
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/v3/api-docs/**")
                                .permitAll()

                                .requestMatchers(HttpMethod.POST, "/api/auth/register",
                                                "/api/auth/login")
                                .permitAll()
                                // Allow guests to view available membership packages
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/packages",
                                                "/api/packages/{id}")
                                .permitAll()
                                // Allow guests to view available sport classes
                                .requestMatchers(HttpMethod.GET, "/api/auth/rooms")
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
                                .requestMatchers(HttpMethod.GET,
                                                "/api/manager/accounts",
                                                "/api/manager/accounts/search",
                                                "/api/manager/accounts/{id}",
                                                "/api/manager/permissions",
                                                "/api/manager/reports/revenue",
                                                "/api/manager/reports/members",
                                                "/api/manager/packages")
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

                                // FACILITY ROOM - MANAGER
                                .requestMatchers(HttpMethod.POST, "/api/auth/rooms")
                                .hasRole("MANAGER")
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/rooms")
                                .hasRole("MANAGER")
                                .requestMatchers(
                                                HttpMethod.PUT,
                                                "/api/rooms/*")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.DELETE,
                                                "/api/rooms/*")
                                                .hasRole("MANAGER")
                                // END FACILITY ROOM
                                
                                // SPORT CATEGORY - MANAGER
                                .requestMatchers(HttpMethod.POST,
                                        "/api/sport-categories")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.PUT,
                                        "/api/sport-categories/*")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.DELETE,
                                        "/api/sport-categories/*")
                                .hasRole("MANAGER")
                                // END SPORT CATEGORY

                                // SPORT CLASS - MANAGER
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/sport-classes",
                                                "/api/sport-classes/*")
                                .hasRole("MANAGER")
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/sport-classes")
                                .hasRole("MANAGER")
                                .requestMatchers(
                                                HttpMethod.PUT,
                                                "/api/sport-classes/*")
                                .hasRole("MANAGER")
                                // END SPORT CLASS

                                // CLASS SCHEDULE
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/class-schedules/coach/me")
                                .hasRole("COACH")
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/class-schedules/**")
                                .authenticated()
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/class-schedules/**")
                                .hasRole("MANAGER")
                                .requestMatchers(
                                                HttpMethod.PUT,
                                                "/api/class-schedules/**")
                                .hasRole("MANAGER")
                                .requestMatchers(
                                                HttpMethod.DELETE,
                                                "/api/class-schedules/**")
                                .hasRole("MANAGER")
                                // END CLASS SCHEDULE

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
                                // Member xem hóa đơn của chính mình.
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/member/payments/me",
                                                "/api/member/payments/*/invoice")
                                .hasRole("MEMBER")

                                // MEMBER - CLASS REGISTRATION
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/member/class-registrations/*")
                                .hasRole("MEMBER")
                                .requestMatchers(
                                                HttpMethod.PATCH,
                                                "/api/member/class-registrations/*/cancel")
                                .hasRole("MEMBER")
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/member/class-registrations/me")
                                .hasRole("MEMBER")
                                // END MEMBER - CLASS REGISTRATION
                                // MEMBER

                                // COACH
                                .requestMatchers(
                                                HttpMethod.PATCH,
                                                "/api/users/me/coach-profile")
                                .hasRole("COACH")
                                // COACH

                                // RECEPTIONIST & MANAGER
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/receptionist/members",
                                                "/api/receptionist/members/*")
                                .hasAnyRole("RECEPTIONIST", "MANAGER")
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/receptionist/members")
                                .hasAnyRole("RECEPTIONIST", "MANAGER")
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/receptionist/members/*/subscriptions",
                                                "/api/receptionist/members/*/class-registrations")
                                .hasAnyRole("RECEPTIONIST", "MANAGER")
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/receptionist/members/*/subscriptions",
                                                "/api/receptionist/members/*/subscriptions/renew",
                                                "/api/receptionist/members/*/subscriptions/upgrade")
                                .hasAnyRole("RECEPTIONIST", "MANAGER")
                                // Receptionist / Manager hủy đơn chờ thanh toán của Member.
                                .requestMatchers(
                                                HttpMethod.PATCH,
                                                "/api/receptionist/members/*/subscriptions/*/cancel")
                                .hasAnyRole("RECEPTIONIST", "MANAGER")
                                // Receptionist / Manager xem lịch sử payment và hóa đơn của Member.
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/receptionist/payments",
                                                "/api/receptionist/payments/*/invoice")
                                .hasAnyRole("RECEPTIONIST", "MANAGER")
                                // Chỉ Receptionist và Manager được xác nhận thu tiền mặt.
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/receptionist/payments/cash")
                                .hasAnyRole("RECEPTIONIST", "MANAGER")
                                // Receptionist đăng ký / hủy lớp giúp Member.
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/receptionist/class-registrations")
                                .hasRole("RECEPTIONIST")
                                .requestMatchers(
                                                HttpMethod.PATCH,
                                                "/api/receptionist/class-registrations/*/cancel")
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
