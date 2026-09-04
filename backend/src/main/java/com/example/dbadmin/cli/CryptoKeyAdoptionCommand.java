package com.example.dbadmin.cli;

import com.example.dbadmin.service.CryptoKeyStore;

import java.io.Console;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;

/** 旧安装把原始主密钥一次性接管到受保护文件的离线命令。 */
public final class CryptoKeyAdoptionCommand {
    private CryptoKeyAdoptionCommand() {
    }

    public static boolean matches(String[] args) {
        return args.length >= 2 && "crypto-key".equals(args[0]) && "adopt".equals(args[1]);
    }

    public static int run(String[] args) {
        Console console = System.console();
        if (console == null) {
            System.err.println("无法安全读取旧主密钥：当前没有可隐藏输入的交互式终端。请在真实终端中运行此命令。");
            return 2;
        }
        return run(args, prompt -> console.readPassword("%s", prompt), System.out, System.err);
    }

    static int run(String[] args, SecretReader reader, PrintStream out, PrintStream err) {
        try {
            Path keyFile = parseKeyFile(args);
            char[] first = reader.read("请输入原 DB_ADMIN_CRYPTO_KEY：");
            char[] second = reader.read("请再次输入旧主密钥：");
            try {
                if (first == null || second == null || first.length == 0) {
                    throw new IllegalArgumentException("旧主密钥不能为空。");
                }
                if (!Arrays.equals(first, second)) {
                    throw new IllegalArgumentException("两次输入的旧主密钥不一致。");
                }
                new CryptoKeyStore().stageAdoption(keyFile, new String(first));
            } finally {
                if (first != null) Arrays.fill(first, '\0');
                if (second != null) Arrays.fill(second, '\0');
            }
            out.println("旧主密钥已写入待验证文件。请正常启动 MyDataDev；历史密文验证通过后，文件会自动生效。");
            return 0;
        } catch (Exception error) {
            err.println("主密钥接管失败：" + error.getMessage());
            return 2;
        }
    }

    private static Path parseKeyFile(String[] args) {
        Path result = Path.of(CryptoKeyStore.DEFAULT_KEY_FILE);
        for (int index = 2; index < args.length; index++) {
            String argument = args[index];
            if (argument.startsWith("--key-file=")) {
                result = Path.of(argument.substring("--key-file=".length()));
                continue;
            }
            if ("--key-file".equals(argument) && index + 1 < args.length) {
                result = Path.of(args[++index]);
                continue;
            }
            throw new IllegalArgumentException("不支持的参数：" + argument);
        }
        return result;
    }

    @FunctionalInterface
    interface SecretReader {
        char[] read(String prompt);
    }
}
