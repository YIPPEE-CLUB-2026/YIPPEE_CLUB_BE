package club.yippee.api.common;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    /** Says the process is up. It does not touch the database. */
    @GetMapping("/api/v1/health/")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /**
     * For the load balancer: 200 only while the database answers, so an instance that has lost
     * its database is taken out of rotation. A failure here is a 500 from the common handler.
     */
    @GetMapping("/api/v1/health/ready")
    Map<String, String> ready() {
        jdbc.sql("select 1").query(Integer.class).single();
        return Map.of("status", "ok");
    }

    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    HealthController(org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.jdbc = jdbc;
    }
}
