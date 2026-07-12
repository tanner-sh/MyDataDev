package com.example.dbadmin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:context-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "app.crypto-key=context-test-crypto-key",
                "app.backup.directory=${java.io.tmpdir}/mydatadev-context-backups"
        }
)
class ApplicationContextTest {
    @Test
    void startsApplicationContextWithProductionServiceWiring() {
    }
}
