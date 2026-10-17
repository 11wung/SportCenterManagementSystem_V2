package com.swp391.sportcentermanagementsystem_v2.config;

import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.security.JwtAuthenticationFilter;
import com.swp391.sportcentermanagementsystem_v2.security.JwtService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
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

                // AUTH AND PUBLIC PACKAGES
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/packages",
                        "/api/packages/{id}")
                .permitAll()

                // MANAGER - GET
                .requestMatchers(
                        HttpMethod.GET,
                        "/api/test/manager",
                        "/api/manager/accounts",
                        "/api/manager/accounts/search",
                        "/api/manager/accounts/{id}",
                        "/api/manager/permissions")
                .hasRole("MANAGER")

                // MANAGER - POST
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/manager/accounts",
                        "/api/manager/packages")
                .hasRole("MANAGER")

                // MANAGER - PATCH
                .requestMatchers(
                        HttpMethod.PATCH,
                        "/api/manager/accounts/*/deactivate",
                        "/api/manager/accounts/*/staff-profile",
                        "/api/manager/accounts/{id}",
                        "/api/manager/packages/{id}",
                        "/api/manager/packages/{id}/status")
                .hasRole("MANAGER")

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
                        "/api/member/subscriptions/renew")
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

                // COACH
                .requestMatchers(
                        HttpMethod.PATCH,
                        "/api/users/me/coach-profile")
                .hasRole("COACH")

                // RECEPTIONIST - GET
                .requestMatchers(
                        HttpMethod.GET,
                        "/api/receptionist/members",
                        "/api/receptionist/members/*")
                .hasRole("RECEPTIONIST")

                .requestMatchers(
                        HttpMethod.GET,
                        "/api/receptionist/members/*/subscriptions")
                .hasRole("RECEPTIONIST")

                .requestMatchers(
                        HttpMethod.GET,
                        "/api/receptionist/members/*/class-registrations")
                .hasRole("RECEPTIONIST")

                // RECEPTIONIST - POST
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/receptionist/members",
                        "/api/receptionist/members/*/subscriptions",
                        "/api/receptionist/members/*/subscriptions/renew")
                .hasRole("RECEPTIONIST")

                .requestMatchers(
                        HttpMethod.POST,
                        "/api/receptionist/class-registrations")
                .hasRole("RECEPTIONIST")

                // RECEPTIONIST - PATCH
                .requestMatchers(
                        HttpMethod.PATCH,
                        "/api/receptionist/class-registrations/*/cancel")
                .hasRole("RECEPTIONIST")

                // OTHER ENDPOINTS
                .anyRequest().authenticated());

        http.csrf(csrf -> csrf.disable());

        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http.formLogin(form -> form.disable());
        http.httpBasic(basic -> basic.disable());

        http.addFilterBefore(
                jwtFilter,
                UsernamePasswordAuthenticationFilter.class);

        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> response
                        .sendError(HttpStatus.UNAUTHORIZED.value())));

        return http.build();
    }
}
