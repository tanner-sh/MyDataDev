package com.example.dbadmin.repo;
import com.example.dbadmin.model.StorageProfile;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
class StorageProfileRepositoryTest {
    @Test void createsAndUpdatesProfileWithMultipleGeneratedColumns() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:storage-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var repository = new StorageProfileRepository(new JdbcTemplate(ds));
        var profile = new StorageProfile(0, "fixture", "NFS", "localhost", 2049, "/qa", "", "", "", "", "/export", 1001, 1001, "", "NONE", "PASSWORD", "", "", "", false, true, null, null, null, null, null);
        long id = repository.insert(profile);
        assertThat(id).isPositive();
        assertThat(repository.findById(id).orElseThrow().createdAt()).isNotNull();
        repository.update(id, profile);
        assertThat(repository.findAll()).hasSize(1);
    }
}
