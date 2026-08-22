package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 连接档案字段。
 *
 * <p>连接多起来之后，一个扁平列表就不够用了：需要按业务线分组、用标签标注用途，也需要把
 * 「这条连接默认用哪个库」「每次建会话先执行什么」记在连接上，而不是每次手动切换。</p>
 */
public class V6__ConnectionProfile extends BaseJavaMigration {
    @Override
    public void migrate(Context context) {
        new ResourceDatabasePopulator(new ClassPathResource("connection-profile-schema.sql")).populate(context.getConnection());
    }
}
