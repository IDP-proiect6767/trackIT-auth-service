package com.trackit.auth.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChangePasswordIT extends BaseIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @Test
    void changePassword_revokesOldRefreshTokens_newPasswordWorks() throws Exception {
        // register + login
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username":"chpwuser","email":"chpw@test.com","password":"oldpassword1"}
                            """))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username":"chpwuser","password":"oldpassword1"}
                            """))
                .andExpect(status().isOk())
                .andReturn();

        var body = mapper.readTree(login.getResponse().getContentAsString());
        String accessToken  = body.at("/tokens/accessToken").asText();
        String refreshToken = body.at("/tokens/refreshToken").asText();

        // change password
        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"currentPassword":"oldpassword1","newPassword":"newpassword1"}
                            """))
                .andExpect(status().isNoContent());

        // old refresh token is now invalid
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        // login with new password works
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username":"chpwuser","password":"newpassword1"}
                            """))
                .andExpect(status().isOk());
    }
}
