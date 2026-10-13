package com.swp391.sportcentermanagementsystem_v2.config;


import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.security.JwtAuthenticationFilter;
import com.swp391.sportcentermanagementsystem_v2.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;
import jakarta.servlet.DispatcherType;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   UserRepository userRepository) throws Exception {
        JwtAuthenticationFilter jwtFilter =
                new JwtAuthenticationFilter(jwtService, userRepository);

        http.authorizeHttpRequests(auth -> auth

                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                .requestMatchers(HttpMethod.POST, "/api/auth/register",
                        "/api/auth/login").permitAll()

                // MANAGER
                .requestMatchers(HttpMethod.GET, "/api/test/manager",
                        "/api/manager/accounts",
                        "/api/manager/accounts/search",
                        "/api/manager/accounts/{id}",
                        "/api/manager/permissions"
                ).hasRole("MANAGER")
                .requestMatchers(HttpMethod.POST,
                        "/api/manager/accounts"
                ).hasRole("MANAGER")
                .requestMatchers(
                        HttpMethod.PATCH,
                        "/api/manager/accounts/*/deactivate",
                        "/api/manager/accounts/*/staff-profile",
                        "/api/manager/accounts/{id}"
                ).hasRole("MANAGER")
                // MANAGER

                //MEMBER
                .requestMatchers(
                        HttpMethod.PATCH,
                        "/api/users/me/member-profile"
                ).hasRole("MEMBER")
                //MEMBER

                //COACH
                .requestMatchers(
                        HttpMethod.PATCH,
                        "/api/users/me/coach-profile"
                ).hasRole("COACH")
                //COACH

                //RECEPTIONIST
                .requestMatchers(
                        HttpMethod.GET,
                        "/api/receptionist/members",
                        "/api/receptionist/members/*"
                ).hasRole("RECEPTIONIST")
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/receptionist/members"
                ).hasRole("RECEPTIONIST")
                //RECEPTIONIST
                .anyRequest().authenticated());


        http.csrf(csrf -> csrf.disable());

        http.sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        );

        http.formLogin(form -> form.disable());
        http.httpBasic(basic -> basic.disable());

        http.addFilterBefore(jwtFilter,
                UsernamePasswordAuthenticationFilter.class);

        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                        response.sendError(HttpStatus.UNAUTHORIZED.value())
                )
        );

        return http.build();
    }


}
