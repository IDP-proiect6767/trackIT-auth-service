package com.trackit.auth.it;

import com.trackit.auth.domain.User;
import com.trackit.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LockoutIT extends BaseIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;

    @Test
    void lockoutAfterFiveFailures_thenUnlocksWhenWindowPasses() throws Exception {
        // register
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username":"lockuser","email":"lock@test.com","password":"password123"}
                            """))
                .andExpect(status().isCreated());

        // 5 wrong-password attempts
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                {"username":"lockuser","password":"wrongpass"}
                                """))
                    .andExpect(status().isUnauthorized());
        }
        // 5th attempt locks the account
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username":"lockuser","password":"wrongpass"}
                            """))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        // correct password also rejected while locked
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username":"lockuser","password":"password123"}
                            """))
                .andExpect(status().isLocked());

        // manually expire the lock in DB
        User user = userRepository.findByUsername("lockuser").orElseThrow();
        user.setLockedUntil(Instant.now().minusSeconds(1));
        userRepository.save(user);

        // now correct password works
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username":"lockuser","password":"password123"}
                            """))
                .andExpect(status().isOk());
    }
}
