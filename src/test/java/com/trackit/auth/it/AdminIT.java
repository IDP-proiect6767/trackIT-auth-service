package com.trackit.auth.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminIT extends BaseIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    private String adminToken() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username":"admin","password":"admin123"}
                            """))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).at("/tokens/accessToken").asText();
    }

    private String registerAndGetToken(String username, String email) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(
                            "{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"password123\"}",
                            username, email)))
                .andExpect(status().isCreated())
                .andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).at("/tokens/accessToken").asText();
    }

    @Test
    void adminCanListAndGetUsers() throws Exception {
        String token = adminToken();
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void nonAdminGets403() throws Exception {
        String userToken = registerAndGetToken("normaluser1", "normal1@test.com");
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminCanDisableUser_andDeleteLastAdminIsRejected() throws Exception {
        String adminTok = adminToken();

        // create a user to disable
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"username":"todelete","email":"todelete@test.com","password":"password123"}
                            """))
                .andExpect(status().isCreated())
                .andReturn();
        long userId = mapper.readTree(reg.getResponse().getContentAsString()).at("/user/id").asLong();

        // disable
        mockMvc.perform(patch("/api/v1/admin/users/" + userId)
                        .header("Authorization", "Bearer " + adminTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // find admin user id
        MvcResult list = mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminTok))
                .andExpect(status().isOk())
                .andReturn();
        var content = mapper.readTree(list.getResponse().getContentAsString()).get("content");
        long adminId = -1;
        for (var node : content) {
            if ("admin".equals(node.get("username").asText())) {
                adminId = node.get("id").asLong();
                break;
            }
        }

        // deleting the last enabled admin must return 409
        mockMvc.perform(delete("/api/v1/admin/users/" + adminId)
                        .header("Authorization", "Bearer " + adminTok))
                .andExpect(status().isConflict());
    }
}
