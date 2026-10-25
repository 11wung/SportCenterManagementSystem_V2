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
                                // Khách xem danh mục class đang mở đăng ký trước khi quyết định đăng nhập
                                .requestMatchers(HttpMethod.GET, "/api/classes", "/api/classes/*")
                                .permitAll()
                                // Khách xem các suất 1-1 còn trống của coach
                                .requestMatchers(HttpMethod.GET, "/api/coach-slots")
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

                                // COURSE - GET cho mọi user đăng nhập, ghi chỉ MANAGER
                                .requestMatchers(HttpMethod.POST,
                                                "/api/courses")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.PUT,
                                                "/api/courses/*")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.PATCH,
                                                "/api/courses/*/active")
                                .hasRole("MANAGER")
                                // Gán môn cho phòng / coach
                                .requestMatchers(HttpMethod.PUT,
                                                "/api/rooms/*/sports",
                                                "/api/coaches/*/sports")
                                .hasRole("MANAGER")
                                // END COURSE

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
                                // END CLASS SCHEDULE

                                // CLASS SCHEDULE / SESSION - ghi chỉ MANAGER, xem sessions cho mọi user đăng nhập
                                .requestMatchers(HttpMethod.PUT,
                                                "/api/sport-classes/*/schedules",
                                                "/api/class-sessions/*")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.POST,
                                                "/api/sport-classes/*/sessions/generate")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.PATCH,
                                                "/api/sport-classes/*/open")
                                .hasRole("MANAGER")
                                // END CLASS SCHEDULE / SESSION

                                // HỦY CLASS (MANAGER) VÀ HOÀN TIỀN (LỄ TÂN, MANAGER)
                                .requestMatchers(HttpMethod.PATCH,
                                                "/api/sport-classes/*/cancel")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.GET,
                                                "/api/refunds")
                                .hasAnyRole("RECEPTIONIST", "MANAGER")
                                .requestMatchers(HttpMethod.POST,
                                                "/api/refunds/*/complete")
                                .hasAnyRole("RECEPTIONIST", "MANAGER")
                                // END HỦY CLASS / HOÀN TIỀN

                                // BUỔI HỌC BỊ HỦY DO TRUNG TÂM, BUỔI BÙ, ĐỔI PHÒNG, ĐỔI COACH (MANAGER)
                                .requestMatchers(HttpMethod.PATCH,
                                                "/api/class-sessions/*/cancel",
                                                "/api/rooms/*/maintenance")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.GET,
                                                "/api/class-sessions/*/makeup-proposals")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.POST,
                                                "/api/class-sessions/*/makeup",
                                                "/api/class-sessions/*/refund-instead",
                                                "/api/sport-classes/*/replace-coach",
                                                "/api/coaches/*/leave",
                                                "/api/coaches/*/cancel-slots")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.POST,
                                                "/api/member/class-sessions/*/decline",
                                                "/api/member/class-registrations/*/leave")
                                .hasRole("MEMBER")
                                // END BUỔI HỌC BỊ HỦY

                                // ĐÁNH GIÁ CUỐI LỚP, ĐÁNH GIÁ THỂ LỰC
                                .requestMatchers(HttpMethod.GET, "/api/coach/classes/*/evaluations")
                                .hasRole("COACH")
                                .requestMatchers(HttpMethod.POST, "/api/coach/classes/*/evaluations/*",
                                                "/api/coach/assessment-slots/*/result")
                                .hasRole("COACH")
                                .requestMatchers(HttpMethod.GET, "/api/member/class-evaluations/me",
                                                "/api/member/assessment-slots/me", "/api/member/fitness-progress/me",
                                                "/api/assessment-slots")
                                .hasRole("MEMBER")
                                .requestMatchers(HttpMethod.POST, "/api/member/assessment-slots/*/book")
                                .hasRole("MEMBER")
                                .requestMatchers(HttpMethod.PATCH, "/api/member/assessment-slots/*/cancel")
                                .hasRole("MEMBER")
                                .requestMatchers(HttpMethod.POST, "/api/manager/assessment-slots",
                                                "/api/manager/assessment-slots/bulk")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.DELETE, "/api/manager/assessment-slots")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.GET, "/api/manager/assessment-capacity")
                                .hasRole("MANAGER")
                                // END ĐÁNH GIÁ

                                // THÔNG BÁO TRONG ỨNG DỤNG: mọi người dùng đã đăng nhập
                                .requestMatchers("/api/notifications/**").authenticated()

                                // BUỔI 1-1
                                .requestMatchers(HttpMethod.POST,
                                                "/api/coach/slots",
                                                "/api/coach/slots/*/cancel")
                                .hasRole("COACH")
                                .requestMatchers(HttpMethod.GET, "/api/coach/slots/me")
                                .hasRole("COACH")
                                .requestMatchers(HttpMethod.DELETE, "/api/coach/slots/*")
                                .hasRole("COACH")
                                .requestMatchers(HttpMethod.PUT, "/api/coaches/*/rank")
                                .hasRole("MANAGER")
                                .requestMatchers(HttpMethod.POST,
                                                "/api/member/coach-slots/*/book",
                                                "/api/member/coach-slots/*/online-payment",
                                                "/api/member/coach-slots/*/online-payment/check")
                                .hasRole("MEMBER")
                                .requestMatchers(HttpMethod.PATCH, "/api/member/coach-slots/*/cancel")
                                .hasRole("MEMBER")
                                .requestMatchers(HttpMethod.GET, "/api/member/coach-slots/me")
                                .hasRole("MEMBER")
                                .requestMatchers(HttpMethod.POST, "/api/receptionist/coach-slots/*/cash-payment")
                                .hasAnyRole("RECEPTIONIST", "MANAGER")
                                .requestMatchers(HttpMethod.PATCH, "/api/receptionist/coach-slots/*/cancel")
                                .hasRole("RECEPTIONIST")
                                // END BUỔI 1-1

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
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/member/class-registrations/*/online-payment",
                                                "/api/member/class-registrations/*/online-payment/check",
                                                "/api/member/class-registrations/*/waitlist")
                                .hasRole("MEMBER")
                                // Xem danh sách chờ của một class
                                .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/sport-classes/*/waitlist")
                                .hasAnyRole("RECEPTIONIST", "MANAGER")
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
                                // Thu tiền mặt cho đăng ký lớp.
                                .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/receptionist/class-registrations/*/cash-payment")
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
