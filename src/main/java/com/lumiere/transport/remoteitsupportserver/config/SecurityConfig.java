package com.lumiere.transport.remoteitsupportserver.config;

import com.lumiere.transport.remoteitsupportserver.auth.security.JwtFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;


@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final JwtFilter jwtFilter;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/login", "/register", "/ws/**", "/agents/login", "/agents/register").permitAll()
                    .requestMatchers(HttpMethod.GET, "/agents", "/agents/online").permitAll()
                        .requestMatchers(HttpMethod.POST, "/sessions/start/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/sessions/start-by-code/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/sessions/stop-by-token/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/sessions/by-token/**").permitAll()
                        .requestMatchers("/chat/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/sessions/approval-public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/sessions/approve-public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/sessions/reject-public/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/sessions/history/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/agents/*/assign/*", "/agents/*/unassign").hasRole("ADMIN")
                        .requestMatchers("/agents/heartbeat", "/agents/offline", "/agents/metrics").hasRole("AGENT")
                        .requestMatchers("/sessions/pending/**").hasRole("AGENT")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Autoriser toutes les origines pour le développement LAN
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}