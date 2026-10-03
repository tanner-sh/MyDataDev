package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static com.example.dbadmin.service.MySqlTablePropertiesService.*;

class MySqlTablePropertiesServiceTest {
    private final Properties original = new Properties("app", "t", "InnoDB", "utf8mb4", "utf8mb4_bin", "10", "0", "0", "0", "v1",
            List.of("InnoDB", "MyISAM"), List.of(new Collation("utf8mb4_bin", "utf8mb4")));
    @Test void validatesVersionAndDoesNotInterpolateArbitraryPropertyValues() {
        assertThatThrownBy(() -> ddl(original, change("InnoDB", "11", "old"), "`app`.`t`")).hasMessageContaining("已变化");
        assertThatThrownBy(() -> ddl(original, change("BLACKHOLE", "11", "v1"), "`app`.`t`")).hasMessageContaining("不可用");
        assertThatThrownBy(() -> ddl(original, change("InnoDB", "11; DROP TABLE t", "v1"), "`app`.`t`")).hasMessageContaining("正整数");
        assertThatThrownBy(() -> ddl(original, change("InnoDB", "9", "v1"), "`app`.`t`")).hasMessageContaining("只允许提高");
        assertThatThrownBy(() -> ddl(original, change("InnoDB", "18446744073709551616", "v1"), "`app`.`t`")).hasMessageContaining("64 位");
    }
    @Test void emitsOneAlterStatementAndRecognizesNoOp() {
        assertThat(ddl(original, change("InnoDB", "10", "v1"), "`app`.`t`")).isEmpty();
        assertThat(ddl(original, change("MyISAM", "100", "v1"), "`app`.`t`")).containsExactly("ALTER TABLE `app`.`t` ENGINE=MyISAM, AUTO_INCREMENT=100");
        var mismatch = new Change("app", "t", "InnoDB", "latin1", "utf8mb4_bin", "10", "v1", "app.t");
        assertThatThrownBy(() -> ddl(original, mismatch, "`app`.`t`")).hasMessageContaining("不匹配");
    }
    private Change change(String engine, String next, String version) {
        return new Change("app", "t", engine, "utf8mb4", "utf8mb4_bin", next, version, "app.t");
    }
}
