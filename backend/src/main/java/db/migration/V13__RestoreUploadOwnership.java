package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 为恢复上传文件增加用户归属；既有上传保留但不猜测归属，不影响数据库连接配置。 */
public class V13__RestoreUploadOwnership extends BaseJavaMigration {
    @Override
    public void migrate(Context context) {
        new ResourceDatabasePopulator(new ClassPathResource("restore-upload-ownership-schema.sql"))
                .populate(context.getConnection());
    }
}
