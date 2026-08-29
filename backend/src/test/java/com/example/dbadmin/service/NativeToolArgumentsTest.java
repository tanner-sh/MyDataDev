package com.example.dbadmin.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeToolArgumentsTest {

    @Test
    void shouldExtractOptionNameFromEveryWritingStyle() {
        assertThat(NativeToolArguments.optionName("--dbname=other")).isEqualTo("--dbname");
        assertThat(NativeToolArguments.optionName("--dbname other")).isEqualTo("--dbname");
        assertThat(NativeToolArguments.optionName("--DBNAME=other")).isEqualTo("--dbname");
        assertThat(NativeToolArguments.optionName("-dother")).isEqualTo("-d");
        assertThat(NativeToolArguments.optionName("-d other")).isEqualTo("-d");
        assertThat(NativeToolArguments.optionName("-d")).isEqualTo("-d");
        assertThat(NativeToolArguments.optionName("userid=scott/tiger")).isEqualTo("userid");
        // 短选项大小写不能折叠：MySQL 的 -p 是密码、-P 是端口。
        assertThat(NativeToolArguments.optionName("-P3307")).isEqualTo("-P");
    }

    @Test
    void shouldBlockPostgresTargetOverrides() {
        for (String arg : new String[]{"--dbname=other", "--dbname other", "-dother", "-d other", "--DBNAME=other"}) {
            assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.PG_RESTORE, arg, "恢复"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能覆盖系统控制参数");
        }
    }

    @Test
    void shouldBlockPostgresOutputFileOverride() {
        for (String arg : new String[]{"--file=/etc/passwd", "-f/etc/passwd", "--format=plain"}) {
            assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.PG_DUMP, arg, "备份"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能覆盖系统控制参数");
        }
    }

    @Test
    void shouldBlockMysqlTargetOverrides() {
        for (String arg : new String[]{"-Dother", "--database=other", "--socket=/tmp/other.sock", "--defaults-file=/tmp/my.cnf"}) {
            assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.MYSQL, arg, "恢复"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能覆盖系统控制参数");
        }
    }

    @Test
    void shouldBlockOracleSystemKeywords() {
        for (String arg : new String[]{"userid=scott/tiger", "file=/tmp/other.dmp", "parfile=/tmp/other.par", "log=/tmp/other.log"}) {
            assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.ORACLE_IMP, arg, "恢复"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能覆盖系统控制参数");
        }
        // 备份范围由任务配置决定，只有 exp 需要拦这些。
        assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.ORACLE_EXP, "full=y", "备份"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(NativeToolArguments.parse(NativeToolLocator.Tool.ORACLE_IMP, "fromuser=a\ntouser=b", "恢复"))
                .containsExactly("fromuser=a", "touser=b");
    }

    /**
     * 这些开关就是冲突策略与事务模型的实现方式，拼在系统参数之后会直接覆盖掉预检里的承诺：
     * --clean 让安全模式照样删对象却不用输连接名确认，--create 把目标换成归档里记的库名，
     * --jobs 与固定加上的 --single-transaction 不兼容，--list 则什么都不恢复。
     */
    @Test
    void shouldBlockPgRestoreSwitchesThatOverrideTheConfirmedPlan() {
        for (String arg : new String[]{
                "--clean", "-c", "--if-exists", "--create", "-C", "--data-only", "-a",
                "--schema-only", "-s", "--single-transaction", "-1", "--jobs=4", "-j4", "--list", "-l"
        }) {
            assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.PG_RESTORE, arg, "恢复"))
                    .as(arg)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能覆盖系统控制参数");
        }
    }

    @Test
    void shouldKeepThoseSameSwitchesUsableForPgDumpWhereTheyAreLegitimate() {
        // 备份侧「备什么」是用户自己的选择，与恢复的冲突策略无关，不能跟着一起拦。
        assertThat(NativeToolArguments.parse(NativeToolLocator.Tool.PG_DUMP, "--schema-only\n--no-owner\n--exclude-table=tmp_%", "备份"))
                .containsExactly("--schema-only", "--no-owner", "--exclude-table=tmp_%");
    }

    @Test
    void shouldKeepToolSpecificFlagsThatOnlyCollideOnOtherTools() {
        // mysqldump 的 -d 是 --no-data，与 pg_dump 的「目标库」同名但语义无关，不该被误伤。
        assertThat(NativeToolArguments.parse(NativeToolLocator.Tool.MYSQLDUMP, "-d", "备份")).containsExactly("-d");
        // pg_restore 的 --exit-on-error / --jobs 是正常用法。
        assertThat(NativeToolArguments.parse(NativeToolLocator.Tool.PG_RESTORE, "--exit-on-error\n--verbose", "恢复"))
                .containsExactly("--exit-on-error", "--verbose");
        assertThat(NativeToolArguments.parse(NativeToolLocator.Tool.MYSQLDUMP, "--single-transaction", "备份"))
                .containsExactly("--single-transaction");
    }

    @Test
    void shouldStillRejectShellControlCharactersAndOversizedInput() {
        assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.PG_DUMP, "--verbose; rm -rf /", "备份"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shell 控制字符");
        assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.PG_DUMP, "--verbose=" + "x".repeat(2_100), "备份"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2000 个字符");
        assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.PG_DUMP, "--verbose\n".repeat(101), "备份"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多填写 100 行");
    }

    /**
     * mysqldump / pg_restore 的位置参数（库名、输入文件）也是系统拼的，额外参数排在它们前面，
     * 一个裸词就能把它们顶掉。
     */
    @Test
    void shouldRejectBarePositionalArgumentsForCommandLineTools() {
        for (String arg : new String[]{"other_database", "file=/tmp/out.sql", "parfile=/tmp/evil.par"}) {
            assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.MYSQLDUMP, arg, "备份"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能覆盖系统控制参数");
            assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.PG_RESTORE, arg, "恢复"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能覆盖系统控制参数");
        }
    }

    /**
     * my_getopt 与 getopt_long 都支持把多个短选项黏成一串，所以一行里可能藏着不止一个选项。
     * 只看前两个字符时 {@code -vP3307} 会被当成无害的 {@code -v} 放行，而 mysqldump 照样把
     * 端口设成 3307，且它排在系统拼的 --port 之后、后者生效 —— 实际备份目标就和预检、生产
     * 确认里显示的不是同一个库了。（本机 mysqldump 9.6 实测确认过这个解析行为。）
     */
    @Test
    void shouldRejectBlockedOptionsHiddenInsideACombinedShortOptionCluster() {
        for (String arg : new String[]{"-vP3307", "-vh10.0.0.1", "-vr/tmp/hijacked.sql", "-vu root", "-cvA"}) {
            assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.MYSQLDUMP, arg, "备份"))
                    .as(arg)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能覆盖系统控制参数");
        }
        for (String arg : new String[]{"-vp5433", "-vd other", "-vf/etc/passwd", "-vU postgres"}) {
            assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.PG_RESTORE, arg, "恢复"))
                    .as(arg)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能覆盖系统控制参数");
        }
    }

    /**
     * 展开必须在「带值的短选项」处停下，否则值里的字母会被当成选项：pg_dump 的
     * {@code -tpublic.users} 里有个 p，不能因此被判成改端口。
     */
    @Test
    void shouldStopExpandingAtTheValueOfAShortOption() {
        assertThat(NativeToolArguments.parse(NativeToolLocator.Tool.PG_DUMP, "-tpublic.users", "备份"))
                .containsExactly("-tpublic.users");
        assertThat(NativeToolArguments.parse(NativeToolLocator.Tool.PG_DUMP, "-nreporting", "备份"))
                .containsExactly("-nreporting");
        // 纯开关串照旧放行：mysqldump 的 -c/-q/-e/-t 都不是系统控制参数。
        assertThat(NativeToolArguments.parse(NativeToolLocator.Tool.MYSQLDUMP, "-cqe", "备份"))
                .containsExactly("-cqe");
        assertThat(NativeToolArguments.optionNames(NativeToolLocator.Tool.MYSQLDUMP, "-vP3307"))
                .containsExactly("-v", "-P");
        assertThat(NativeToolArguments.optionNames(NativeToolLocator.Tool.PG_DUMP, "-tpublic"))
                .containsExactly("-t");
    }

    /** -A/-B 是 --all-databases/--databases 的短写法，长写法已经拦了，短写法不能漏。 */
    @Test
    void shouldBlockMysqlScopeOverridesInTheirShortForm() {
        for (String arg : new String[]{"-A", "-B", "-Bother_db"}) {
            assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.MYSQLDUMP, arg, "备份"))
                    .as(arg)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能覆盖系统控制参数");
        }
    }

    /** 光秃秃一个 "-" 会被工具当成位置参数，和裸词一样得拦。 */
    @Test
    void shouldRejectABareDash() {
        assertThatThrownBy(() -> NativeToolArguments.parse(NativeToolLocator.Tool.MYSQLDUMP, "-", "备份"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能覆盖系统控制参数");
    }

    @Test
    void shouldSkipBlocklistWhenNoNativeToolIsInvolved() {
        // SQL 备份不会拼命令行，只做通用检查。
        assertThat(NativeToolArguments.parse(null, "--dbname=other", "备份")).containsExactly("--dbname=other");
        assertThat(NativeToolArguments.normalize(null, "  \n  ", "备份")).isNull();
    }
}
