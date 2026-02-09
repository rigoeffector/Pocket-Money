package com.pocketmoney.pocketmoney.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

import com.pocketmoney.pocketmoney.security.JwtAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(
                "http://localhost:5173",
                "http://localhost:5174",
                "http://localhost:5175",
                "http://localhost:5176",
                "http://localhost:5177",
                "http://localhost:5178",
                "http://localhost:5179",
                "http://localhost:5180",
                "http://localhost:5181",
                "http://localhost:5182",
                "http://localhost:5183",
                "http://localhost:5184",
                "http://localhost:3000",
                "http://localhost:3001",
                "http://192.168.1.3:8383",
                "http://192.168.1.4:8383",
                "http://64.23.203.249:8383",
                "http://64.23.203.249",
                "http://164.92.89.74:8383",
                "http://164.92.89.74",
                "https://164.92.89.74:8383",
                "https://164.92.89.74",
                "http://64.23.137.36/",
                "https://pochi.info"
        ));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/auth/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/auth/switch-merchant/**").hasRole("RECEIVER")
                        .requestMatchers("/api/auth/switch-back").hasRole("RECEIVER")
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/v1/payments/callback").permitAll() // BizaoPayment webhook callback
                        .requestMatchers("/api/payments/top-up").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/payments/top-up-by-phone").hasAnyRole("USER", "RECEIVER", "ADMIN")
                        .requestMatchers("/api/payments/merchant/top-up").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/payments/pay/customer").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/payments/loans/user/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/payments/loans/merchant/**").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/payments/loans/pay").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/payments/loans/update").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/payments/cards/*").hasAnyRole("USER", "RECEIVER", "ADMIN")
                        .requestMatchers("/api/payments/bonus-history/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/payments/admin-income").hasRole("ADMIN")
                        .requestMatchers("/api/payments/admin/dashboard-statistics").hasRole("ADMIN")
                        .requestMatchers("/api/payments/transactions/receiver/*/export").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/payments/transactions/receiver/**").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/receivers/*/assign-balance").hasRole("ADMIN")
                        .requestMatchers("/api/receivers/*/balance-history/*/approve").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/receivers/*/balance-history").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/receivers/*/wallet").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/receivers/**").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/users/phone/*/nfc-card/**").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/users/*/nfc-card/**").hasAnyRole("USER", "RECEIVER", "ADMIN")
                        .requestMatchers("/api/efashe/initiate").hasAnyRole("USER", "RECEIVER", "ADMIN")
                        .requestMatchers("/api/efashe/initiate-for-other").hasAnyRole("USER", "RECEIVER", "ADMIN")
                        .requestMatchers("/api/efashe/process/**").hasAnyRole("USER", "RECEIVER", "ADMIN")
                        .requestMatchers("/api/efashe/status/**").hasAnyRole("USER", "RECEIVER", "ADMIN")
                        .requestMatchers("/api/efashe/refund/**").hasRole("ADMIN")
                        .requestMatchers("/api/efashe/refund-history").hasRole("ADMIN")
                        .requestMatchers("/api/efashe/receipt/**").hasAnyRole("USER", "RECEIVER", "ADMIN")
                        .requestMatchers("/api/efashe/transactions", "/api/efashe/transactions/**").hasAnyRole("USER", "RECEIVER", "ADMIN")
                        .requestMatchers("/api/efashe/electricity/tokens").hasAnyRole("USER", "RECEIVER", "ADMIN")
                        .requestMatchers("/api/efashe/settings", "/api/efashe/settings/**").hasRole("ADMIN")
                        .requestMatchers("/api/qrcode/**").hasAnyRole("RECEIVER", "ADMIN")
                        .requestMatchers("/api/test/**").hasAnyRole("USER", "RECEIVER", "ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

