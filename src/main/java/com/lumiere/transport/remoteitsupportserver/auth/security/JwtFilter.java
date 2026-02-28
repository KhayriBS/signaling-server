package com.lumiere.transport.remoteitsupportserver.auth.security;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Component

public class JwtFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserDetailsServiceImpl userDetailsService;

    public JwtFilter(JwtProvider jwtProvider, UserDetailsServiceImpl userDetailsService) {
        this.jwtProvider = jwtProvider;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // ✅ exclure aussi /agents/login et /agents/register (facultatif mais clean)
        return path.startsWith("/auth")
                || path.equals("/agents/login")
                || path.equals("/agents/register");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        try {
            if (!jwtProvider.isValid(token)) {
                chain.doFilter(request, response);
                return;
            }

            String subject = jwtProvider.getSubject(token); // username OR machineId
            String role = jwtProvider.getRole(token);       // ROLE_USER / ROLE_AGENT / ...

            UsernamePasswordAuthenticationToken authentication;

            if ("ROLE_AGENT".equals(role)) {
                // ✅ Auth agent: principal = machineId, authorities = ROLE_AGENT
                var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_AGENT"));
                authentication = new UsernamePasswordAuthenticationToken(subject, null, authorities);
            } else {
                // ✅ Auth user normal
                UserDetails user = userDetailsService.loadUserByUsername(subject);
                authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            }

            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (Exception e) {
            // IMPORTANT: ne pas bloquer brutalement, on clear et on laisse Spring décider
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }
    }

