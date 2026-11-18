package kr.tave.minicore.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.json.JsonCompareMode.STRICT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ingest.run-on-startup=false",
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.validation-timeout=500"
})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HealthApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousHealthReportsUpThenServiceUnavailableWhenDatabaseStops() throws Exception {
        // No token or Principal: deployment probes must work without login.
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", STRICT));

        // This container belongs only to this test; never stop the developer's DB.
        postgres.stop();

        mvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"DOWN\"}", STRICT));
    }
}
