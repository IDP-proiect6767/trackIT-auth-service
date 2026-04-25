package com.trackit.auth.security;

import com.trackit.auth.config.JwtProperties;
import com.trackit.auth.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties jwtProperties;
    private SecretKey signingKey;

    @PostConstruct
    void init() {
        signingKey = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(User user) {
        long nowMs = System.currentTimeMillis();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name())
                .claim("type", "access")
                .issuer(jwtProperties.getIssuer())
                .issuedAt(new Date(nowMs))
                .expiration(new Date(nowMs + jwtProperties.getAccessTtlSeconds() * 1000))
                .signWith(signingKey)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Optional<Claims> tryParse(String token) {
        try {
            return Optional.of(parse(token));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
