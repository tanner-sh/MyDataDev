package com.example.dbadmin.repo;

import com.example.dbadmin.model.RestoreUpload;
import com.example.dbadmin.model.RestoreJob;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RestoreIdentityRepositoryTest {
    @Test
    void returnsIdentityWhenH2AlsoGeneratesCreatedAt() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:restore-identity-" + UUID.randomUUID(), "sa", "");
        ds.setUrl(ds.getUrl() + ";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var uploads = new RestoreUploadRepository(jdbc);
        long uploadId = uploads.insert(new RestoreUpload(0, "test.sql", "/tmp/test.sql", 10, "abc", "SQL", "h2", null, null, Instant.now().plusSeconds(3600)));
        assertThat(uploadId).isPositive();
        assertThat(uploads.findById(uploadId).orElseThrow().createdAt()).isNotNull();
        var jobs = new RestoreJobRepository(jdbc);
        long jobId = jobs.insert(new RestoreJob(0, "UPLOAD", uploadId, "test.sql", "/tmp/test.sql", "abc", "SQL", "h2", 1, "h2", "FAIL", "{}", "QUEUED", "QUEUED", 0L, null, "queued", false, "tester", null, null, null));
        assertThat(jobId).isPositive();
        assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo("QUEUED");
        assertThat(jobs.findById(jobId).orElseThrow().createdAt()).isNotNull();
    }
}
