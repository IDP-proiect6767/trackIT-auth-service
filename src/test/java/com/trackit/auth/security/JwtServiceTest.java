package com.trackit.auth.security;

import com.trackit.auth.config.JwtProperties;
import com.trackit.auth.domain.Role;
import com.trackit.auth.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long!!";

    private JwtService jwtService;
    private JwtProperties props;

    @BeforeEach
    void setUp() {
        props = new JwtProperties();
        props.setSecret(SECRET);
        props.setAccessTtlSeconds(900);
        props.setRefreshTtlSeconds(604800);
        props.setIssuer("trackit-auth");
        props.validate();

        jwtService = new JwtService(props);
        jwtService.init();
    }

    private User testUser() {
        return User.builder()
                .id(42L)
                .username("alice")
                .role(Role.USER)
                .build();
    }

    @Test
    void generateAndParse_roundtrip_recoversAllClaims() {
        String token = jwtService.generateAccessToken(testUser());
        Claims claims = jwtService.parse(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("username", String.class)).isEqualTo("alice");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
        assertThat(claims.get("type", String.class)).isEqualTo("access");
        assertThat(claims.getIssuer()).isEqualTo("trackit-auth");
    }

    @Test
    void parse_expiredToken_throws() {
        props.setAccessTtlSeconds(-1);
        String token = jwtService.generateAccessToken(testUser());

        assertThatThrownBy(() -> jwtService.parse(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parse_tamperedPayload_throws() {
        String token = jwtService.generateAccessToken(testUser());
        // flip a char in the payload (middle section)
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "XXXX" + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parse(tampered))
                .isInstanceOf(Exception.class);
    }

    @Test
    void parse_wrongSignatureKey_throws() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-secret-key-at-least-32-bytes!!!!!".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("99")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(otherKey)
                .compact();

        assertThatThrownBy(() -> jwtService.parse(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tryParse_invalidToken_returnsEmpty() {
        Optional<Claims> result = jwtService.tryParse("not.a.jwt");
        assertThat(result).isEmpty();
    }

    @Test
    void tryParse_validToken_returnsClaims() {
        String token = jwtService.generateAccessToken(testUser());
        Optional<Claims> result = jwtService.tryParse(token);
        assertThat(result).isPresent();
        assertThat(result.get().getSubject()).isEqualTo("42");
    }

    @Test
    void validate_secretTooShort_throwsOnStartup() {
        JwtProperties shortProps = new JwtProperties();
        shortProps.setSecret("tooshort");

        assertThatThrownBy(shortProps::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
