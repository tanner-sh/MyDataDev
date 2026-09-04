package com.example.dbadmin.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("desktop")
@SpringBootTest(properties = {
        "MYDATADEV_DESKTOP_HOME=${java.io.tmpdir}/mydatadev-desktop-profile-test",
        "MYDATADEV_DESKTOP_CONTROL_TOKEN=desktop-profile-control-token",
        "MYDATADEV_DESKTOP_PARENT_PID=1",
        "app.crypto-key-source=FILE",
        "app.crypto-key-file=target/test-secrets/desktop-profile-test.key",
        "spring.datasource.url=jdbc:h2:mem:desktop-profile-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "logging.file.name=${java.io.tmpdir}/mydatadev-desktop-profile-test/mydatadev.log"
})
class DesktopProfileIntegrationTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void loadsDesktopOnlyLifecycleAndLocalServerConfiguration() {
        assertThat(context.getBean(DesktopLifecycleService.class)).isNotNull();
        assertThat(context.getBean(DesktopControlController.class)).isNotNull();
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("server.port")).isEqualTo("5173");
        assertThat(environment.getProperty("spring.h2.console.enabled")).isEqualTo("false");
    }
}
