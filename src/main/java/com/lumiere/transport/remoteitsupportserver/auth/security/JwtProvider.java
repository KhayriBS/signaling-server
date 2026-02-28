package com.lumiere.transport.remoteitsupportserver.auth.security;

import com.lumiere.transport.remoteitsupportserver.agent.entity.Agent;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtProvider {
    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expirationMs;

    private byte[] key() {
        return secret.getBytes();
    }

    public String generateToken(UserDetails user) {
        String role = user.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority())
                .orElse("ROLE_USER");

        return Jwts.builder()
                .setSubject(user.getUsername())
                .claim("role", role)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(Keys.hmacShaKeyFor(key()), SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateTokenAgent(Agent agent) {
        return Jwts.builder()
                .setSubject(agent.getMachineId())
                .claim("role", "ROLE_AGENT") // ✅ important
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(Keys.hmacShaKeyFor(key()), SignatureAlgorithm.HS256)
                .compact();
    }

    public io.jsonwebtoken.Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(key()))
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public boolean isValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getSubject(String token) {
        return getClaims(token).getSubject();
    }

    public String getRole(String token) {
        Object r = getClaims(token).get("role");
        return r == null ? "" : r.toString();
    }

}
