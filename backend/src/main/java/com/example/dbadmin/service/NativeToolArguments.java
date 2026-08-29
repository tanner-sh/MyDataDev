package com.example.dbadmin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 原生备份/恢复工具「额外参数」的校验。
 *
 * <p>额外参数是拼进 mysqldump / pg_restore / imp 命令行的，而命令行里同一个选项出现两次时
 * 后者生效 —— 系统自己拼的 --host/--dbname/--file 全部排在额外参数之前。所以一条没被拦住的
 * {@code --dbname=别的库} 就能让预检、生产确认里显示的目标连接与真正被写入的库不是同一个，
 * 一条 {@code --file=/etc/...} 能让备份写到任意路径。</p>
 *
 * <p>拦截必须按工具区分，不能用一份大黑名单：同一个短选项在不同工具里含义相反 ——
 * {@code -d} 在 pg_dump 里是「目标库」（必须拦），在 mysqldump 里是 --no-data（合法用法）。</p>
 *
 * <p>识别选项名时要覆盖四种写法：{@code --dbname=x}、{@code --dbname x}、{@code -dx}，
 * 以及把多个短选项黏在一起的 {@code -vdx} —— my_getopt（MySQL）与 getopt_long（PostgreSQL）
 * 都支持后者，所以一行里可能藏着不止一个选项。每一行整体作为一个 argv 元素传给工具，
 * 所以只看行首那个 token 就够了，但那个 token 必须被完整展开。</p>
 */
final class NativeToolArguments {
    private static final int MAX_ARG_LENGTH = 2_000;
    private static final int MAX_ARGS = 100;

    /** MySQL 系：mysqldump 备份与 mysql 客户端恢复。 */
    private static final Set<String> MYSQL_BLOCKED_LONG = Set.of(
            "--host", "--port", "--user", "--password", "--socket", "--protocol",
            "--defaults-file", "--defaults-extra-file", "--login-path",
            "--database", "--databases", "--all-databases", "--tables", "--result-file"
    );
    /** 与上面的长选项一一对应：-A 是 --all-databases，-B 是 --databases，同样会改写备份范围。 */
    private static final Set<String> MYSQL_BLOCKED_SHORT = Set.of("-h", "-P", "-p", "-u", "-S", "-r", "-D", "-A", "-B");

    /** PostgreSQL 系共有的连接与文件控制项：pg_dump 备份与 pg_restore 恢复都不能被改写。 */
    private static final Set<String> POSTGRES_BLOCKED_LONG = Set.of(
            "--host", "--port", "--username", "--password", "--dbname", "--file", "--format"
    );
    private static final Set<String> POSTGRES_BLOCKED_SHORT = Set.of("-h", "-p", "-U", "-W", "-d", "-f", "-F");

    /**
     * pg_restore 专有的拦截项：这些开关本身就是「已有对象处理」策略和事务模型的实现方式。
     *
     * <p>放行它们等于让额外参数绕过预检里做的所有承诺 —— 它们拼在系统参数之后，后者生效：
     * {@code --clean} 让安全模式照样删对象，却不会触发覆盖模式那道输入连接名的确认；
     * {@code --create} 把恢复目标换成归档里记录的库名，而不是用户确认过的 --dbname；
     * {@code --jobs} 与我们固定加的 {@code --single-transaction} 明确不兼容，预检通过之后
     * 必然执行失败；{@code --list} 则只打印目录、什么都不恢复，任务却会记成功。
     * {@code --data-only} / {@code --schema-only} 由冲突策略决定，也不该由用户另外指定。</p>
     */
    private static final Set<String> PG_RESTORE_BLOCKED_LONG = Set.of(
            "--clean", "--if-exists", "--create", "--data-only", "--schema-only",
            "--single-transaction", "--jobs", "--list"
    );
    private static final Set<String> PG_RESTORE_BLOCKED_SHORT = Set.of("-c", "-C", "-a", "-s", "-1", "-j", "-l");

    /** Oracle 系用 parfile 关键字而不是 - 选项；系统控制的是身份、文件与日志。 */
    private static final Set<String> ORACLE_BLOCKED = Set.of("userid", "file", "log", "parfile");
    /** exp 的备份范围也由系统按任务配置决定。 */
    private static final Set<String> ORACLE_EXPORT_BLOCKED = Set.of("owner", "tables", "full");

    /**
     * 「后面跟值」的短选项：展开一个短选项串时遇到它就停，剩下的字符是值而不是选项。
     *
     * <p>这张表只影响误报，不影响拦截：漏了一个条目，展开会继续往值里走，最多把一个合法参数
     * 当成被拦的选项拒掉（用户改写成长选项即可）；绝不会反过来放过一个该拦的选项。所以宁可
     * 列少也不要为了「让某个参数能过」而乱加。</p>
     *
     * <p>反过来，不列它才会出事：pg_dump 的 {@code -tpublic.users} 里那个 p 只是表名的字母，
     * 若不在 {@code -t} 处停下就会被误判成改端口。</p>
     */
    private static final Set<String> MYSQLDUMP_VALUE_SHORT = Set.of("-h", "-P", "-u", "-p", "-S", "-r", "-T", "-w", "-#");
    private static final Set<String> MYSQL_VALUE_SHORT = Set.of("-h", "-P", "-u", "-p", "-S", "-D", "-e", "-#");
    private static final Set<String> PG_DUMP_VALUE_SHORT = Set.of(
            "-h", "-p", "-U", "-d", "-f", "-F", "-j", "-Z", "-n", "-N", "-t", "-T", "-S", "-e"
    );
    private static final Set<String> PG_RESTORE_VALUE_SHORT = Set.of(
            "-h", "-p", "-U", "-d", "-f", "-F", "-j", "-n", "-N", "-t", "-T", "-S", "-L", "-P", "-I"
    );

    private NativeToolArguments() {
    }

    /**
     * 校验并拆分额外参数。
     *
     * @param tool    目标工具；{@code null} 表示这次不会真的拼命令行（例如 SQL 备份），
     *                此时只做长度、条数与 shell 控制字符的通用检查。
     * @param subject 报错文案里的主语，「备份」或「恢复」。
     */
    static List<String> parse(NativeToolLocator.Tool tool, String raw, String subject) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> args = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String arg = line.trim();
            if (arg.isEmpty()) continue;
            if (arg.length() > MAX_ARG_LENGTH) {
                throw new IllegalArgumentException("单个" + subject + "额外参数不能超过 " + MAX_ARG_LENGTH + " 个字符。");
            }
            if (args.size() >= MAX_ARGS) {
                throw new IllegalArgumentException(subject + "额外参数最多填写 " + MAX_ARGS + " 行。");
            }
            if (arg.matches(".*[|&;<>`$].*")) {
                throw new IllegalArgumentException(subject + "额外参数包含不允许的 shell 控制字符：" + arg);
            }
            if (blocked(tool, arg)) {
                throw new IllegalArgumentException(subject + "额外参数不能覆盖系统控制参数：" + arg);
            }
            args.add(arg);
        }
        return args;
    }

    /** 拼回规范化文本用于持久化；没有参数时返回 null。 */
    static String normalize(NativeToolLocator.Tool tool, String raw, String subject) {
        List<String> args = parse(tool, raw, subject);
        return args.isEmpty() ? null : String.join("\n", args);
    }

    /**
     * 取出一行参数里的第一个选项名。
     *
     * <p>长选项统一转小写（工具本身大小写敏感，转小写只是让 {@code --DBNAME=x} 这种绕过写法
     * 同样落进黑名单）；短选项保持原样，因为 {@code -p} 与 {@code -P} 在 MySQL 里是两个选项。</p>
     *
     * <p>注意这里只给出**第一个**选项：一个短选项串里可能还黏着别的选项，拦截判定必须用
     * {@link #optionNames}，不能只看这一个。</p>
     */
    static String optionName(String arg) {
        String token = firstToken(arg);
        if (token.startsWith("--")) return token.toLowerCase(Locale.ROOT);
        // 短选项可以把值黏在后面（-hlocalhost、-psecret），只有前两个字符是选项本身。
        if (token.startsWith("-") && token.length() > 2) return token.substring(0, 2);
        if (token.startsWith("-")) return token;
        return token.toLowerCase(Locale.ROOT);
    }

    /** 行首那个 argv token：到 {@code =} 或空白为止。 */
    private static String firstToken(String arg) {
        int end = arg.length();
        for (int index = 0; index < arg.length(); index++) {
            char current = arg.charAt(index);
            if (current == '=' || Character.isWhitespace(current)) {
                end = index;
                break;
            }
        }
        return arg.substring(0, end);
    }

    /**
     * 展开一行参数隐含的全部选项名。
     *
     * <p>短选项可以黏成一串：{@code -vP3307} 对 mysqldump 就是 {@code -v -P 3307}。只取前两个
     * 字符的话它会被当成无害的 {@code -v} 放行，而工具照样把端口改成 3307 —— 而且额外参数排在
     * 系统拼的 {@code --port} 之后，命令行里后者生效，于是真正的备份目标与预检、生产确认里
     * 显示的不是同一个库。所以短选项串必须逐字符展开，其中任何一个落进黑名单就整行拒绝。</p>
     *
     * <p>展开在遇到带值的短选项时停止，后面的字符属于值。长选项与 Oracle 的 keyword=value
     * 一行只可能是一个选项，原样返回。</p>
     */
    static List<String> optionNames(NativeToolLocator.Tool tool, String arg) {
        String token = firstToken(arg);
        if (!token.startsWith("-") || token.startsWith("--")) return List.of(optionName(arg));
        List<String> names = new ArrayList<>();
        for (int index = 1; index < token.length(); index++) {
            String name = "-" + token.charAt(index);
            names.add(name);
            if (takesValue(tool, name)) break;
        }
        // 光秃秃一个 "-"：不是选项，交给 isOption 当裸词拒掉。
        return names.isEmpty() ? List.of(token) : names;
    }

    private static boolean takesValue(NativeToolLocator.Tool tool, String option) {
        if (tool == null) return false;
        return switch (tool) {
            case MYSQLDUMP -> MYSQLDUMP_VALUE_SHORT.contains(option);
            case MYSQL -> MYSQL_VALUE_SHORT.contains(option);
            case PG_DUMP -> PG_DUMP_VALUE_SHORT.contains(option);
            case PG_RESTORE -> PG_RESTORE_VALUE_SHORT.contains(option);
            case ORACLE_EXP, ORACLE_IMP -> false;
        };
    }

    private static boolean blocked(NativeToolLocator.Tool tool, String arg) {
        if (tool == null) return false;
        return switch (tool) {
            case MYSQLDUMP, MYSQL, PG_DUMP, PG_RESTORE -> {
                List<String> names = optionNames(tool, arg);
                // 裸词会顶掉系统拼在后面的位置参数（库名 / 输入文件），和改 --dbname 等效。
                if (!isOption(names.get(0))) yield true;
                yield names.stream().anyMatch(name -> blockedOption(tool, name));
            }
            case ORACLE_EXP -> {
                String keyword = optionName(arg);
                yield ORACLE_BLOCKED.contains(keyword) || ORACLE_EXPORT_BLOCKED.contains(keyword);
            }
            case ORACLE_IMP -> ORACLE_BLOCKED.contains(optionName(arg));
        };
    }

    private static boolean blockedOption(NativeToolLocator.Tool tool, String option) {
        return switch (tool) {
            case MYSQLDUMP, MYSQL -> MYSQL_BLOCKED_LONG.contains(option) || MYSQL_BLOCKED_SHORT.contains(option);
            case PG_DUMP -> POSTGRES_BLOCKED_LONG.contains(option) || POSTGRES_BLOCKED_SHORT.contains(option);
            case PG_RESTORE -> POSTGRES_BLOCKED_LONG.contains(option) || POSTGRES_BLOCKED_SHORT.contains(option)
                    || PG_RESTORE_BLOCKED_LONG.contains(option) || PG_RESTORE_BLOCKED_SHORT.contains(option);
            case ORACLE_EXP, ORACLE_IMP -> false;
        };
    }

    /**
     * mysqldump / pg_restore 的位置参数同样是系统控制的（要备份哪个库、要恢复哪个文件），
     * 而额外参数就拼在它们前面。所以这两族工具只接受以 - 开头的选项：一个裸词会顶掉库名或
     * 输入文件名，效果和改 --dbname 一样。Oracle 的 exp/imp 没有位置参数，全是 keyword=value。
     */
    private static boolean isOption(String option) {
        return option.length() > 1 && option.startsWith("-");
    }
}
