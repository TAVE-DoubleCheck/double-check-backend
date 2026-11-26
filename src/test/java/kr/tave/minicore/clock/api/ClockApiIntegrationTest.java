package kr.tave.minicore.clock.api;

import kr.tave.minicore.auth.domain.Role;
import kr.tave.minicore.auth.security.JwtProvider;
import kr.tave.minicore.auth.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "ingest.run-on-startup=false")
@AutoConfigureMockMvc
@Testcontainers
class ClockApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    // 로그인 모듈 연결 후: MockMvc principal 대신 실제 JWT를 Authorization 헤더로 보낸다
    @Autowired
    JwtProvider jwtProvider;

    @Test
    void authenticatedUserReadsDatabaseBusinessDateWithoutChangingIt() throws Exception {
        LocalDate businessDate = databaseDate();

        mvc.perform(get("/api/clock").header(HttpHeaders.AUTHORIZATION, bearer(Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"businessDate\":\"" + businessDate + "\"}", true))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        assertThat(databaseDate()).isEqualTo(businessDate);
    }

    @Test
    void nextRequestReadsUpdatedBusinessDateInsteadOfMachineDateOrCachedDate() throws Exception {
        LocalDate initial = databaseDate();
        LocalDate advanced = initial.plusYears(10);

        mvc.perform(get("/api/clock").header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value(initial.toString()));

        jdbc.update("UPDATE system_clock SET business_date = ? WHERE id = 1", advanced);

        mvc.perform(get("/api/clock").header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value(advanced.toString()));
    }

    @Test
    void anonymousRequestReturnsUnauthorizedInCommonErrorFormat() throws Exception {
        mvc.perform(get("/api/clock"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void unverifiedBearerHeaderDoesNotGrantAccess() throws Exception {
        mvc.perform(get("/api/clock").header(HttpHeaders.AUTHORIZATION, "Bearer unverified-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String bearer(Role role) {
        return "Bearer " + jwtProvider.issue(new LoginUser(1L, role.name().toLowerCase() + "@test.local", role));
    }

    private LocalDate databaseDate() {
        return jdbc.queryForObject("SELECT business_date FROM system_clock WHERE id = 1", LocalDate.class);
    }
}
