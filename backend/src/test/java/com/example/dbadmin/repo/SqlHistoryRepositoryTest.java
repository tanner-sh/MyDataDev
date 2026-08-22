package com.example.dbadmin.repo;

import com.example.dbadmin.dto.ApiDtos.SqlHistoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqlHistoryRepositoryTest {
    private SqlHistoryRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:sql-history-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        repository = new SqlHistoryRepository(new JdbcTemplate(dataSource));
    }

    @Test
    void filtersBySqlTextAcrossTheWholeRetainedHistory() {
        repository.insert(1, "SELECT * FROM orders", "EXECUTE", "SUCCESS", 5, null, "admin");
        repository.insert(1, "SELECT * FROM users", "EXECUTE", "SUCCESS", 5, null, "admin");
        repository.insert(1, "UPDATE orders SET paid = 1", "EXECUTE", "SUCCESS", 5, null, "admin");

        List<SqlHistoryResponse> matches = repository.findRecent(1, "orders", 50);

        assertThat(matches).hasSize(2);
        assertThat(matches).allSatisfy(item -> assertThat(item.sql()).contains("orders"));
    }

    @Test
    void filtersByErrorMessageToo() {
        repository.insert(1, "SELECT 1", "EXECUTE", "SUCCESS", 5, null, "admin");
        repository.insert(1, "SELECT bad", "EXECUTE", "FAILED", 5, "Column BAD not found", "admin");

        assertThat(repository.findRecent(1, "not found", 50))
                .singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo("FAILED"));
    }

    @Test
    void matchesCaseInsensitively() {
        repository.insert(1, "SELECT * FROM Orders", "EXECUTE", "SUCCESS", 5, null, "admin");

        assertThat(repository.findRecent(1, "ORDERS", 50)).hasSize(1);
        assertThat(repository.findRecent(1, "orders", 50)).hasSize(1);
    }

    @Test
    void treatsLikeMetacharactersInTheKeywordAsLiterals() {
        repository.insert(1, "SELECT * FROM orders", "EXECUTE", "SUCCESS", 5, null, "admin");
        repository.insert(1, "SELECT '100%' AS ratio", "EXECUTE", "SUCCESS", 5, null, "admin");

        // 未转义时 "%" 会匹配到全部记录。
        assertThat(repository.findRecent(1, "100%", 50)).hasSize(1);
        assertThat(repository.findRecent(1, "_", 50)).isEmpty();
    }

    @Test
    void keepsTheConnectionScopeWhenFiltering() {
        repository.insert(1, "SELECT * FROM orders", "EXECUTE", "SUCCESS", 5, null, "admin");
        repository.insert(2, "SELECT * FROM orders", "EXECUTE", "SUCCESS", 5, null, "admin");

        assertThat(repository.findRecent(1, "orders", 50)).hasSize(1);
    }

    @Test
    void returnsTheMostRecentEntriesFirstAndCapsTheLimit() {
        for (int index = 0; index < 5; index++) {
            repository.insert(1, "SELECT " + index, "EXECUTE", "SUCCESS", 5, null, "admin");
        }

        assertThat(repository.findRecent(1, null, 2))
                .extracting(SqlHistoryResponse::sql)
                .containsExactly("SELECT 4", "SELECT 3");
        assertThat(repository.findRecent(1, null, 10_000)).hasSize(5);
    }

    @Test
    void blankKeywordBehavesLikeNoFilter() {
        repository.insert(1, "SELECT 1", "EXECUTE", "SUCCESS", 5, null, "admin");

        assertThat(repository.findRecent(1, "   ", 50)).hasSize(1);
    }
}
