package com.example.dbadmin.service;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 一边把备份文件读完，一边算出校验所需的三件事：字节数、SHA-256、尾部长什么样。
 *
 * <p>做成 {@link OutputStream} 是为了复用现成的下载路径 —— 本地文件和远端文件服务都能往一个
 * {@code OutputStream} 里写，这里就不必分别处理，也不必先把可能几个 GB 的文件落到本地。</p>
 *
 * <p>纯逻辑（不碰数据库、不碰网络），所以判定规则可以单测。</p>
 */
final class BackupVerification extends OutputStream {
    /** 尾部保留多少字节用于完整性探测。够看清最后一条语句是不是完整的。 */
    static final int TAIL_BYTES = 512;

    private final MessageDigest digest;
    private final byte[] tail = new byte[TAIL_BYTES];
    private long size;
    private int tailLength;
    private int tailStart;

    BackupVerification() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }

    @Override
    public void write(int value) {
        write(new byte[]{(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
        digest.update(buffer, offset, length);
        size += length;
        appendTail(buffer, offset, length);
    }

    long size() {
        return size;
    }

    /** 只能取一次：{@code digest()} 会顺带重置摘要状态。 */
    String checksum() {
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 文件末尾的一小段文本，用来判断文本备份是不是写完了。 */
    String tailText() {
        byte[] ordered = new byte[tailLength];
        for (int index = 0; index < tailLength; index++) {
            ordered[index] = tail[(tailStart + index) % TAIL_BYTES];
        }
        return new String(ordered, StandardCharsets.UTF_8);
    }

    /**
     * 文本备份的尾部是不是「像写完了」。
     *
     * <p>只看最后一个有内容的行：以分号或注释结尾说明最后一条语句是完整的；停在一条语句中间
     * 的文件，几乎一定是备份进程被杀或磁盘写满留下的半成品。</p>
     *
     * <p>这条判断刻意只用来给提示，不用来判「失败」—— 各家 dump 的收尾写法不完全一致，
     * 把它当硬性结论会把好文件说成坏文件。真正的结论由 SHA-256 给出。</p>
     */
    static boolean looksComplete(String tailText) {
        if (tailText == null) return false;
        String[] lines = tailText.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (line.isEmpty()) continue;
            return line.endsWith(";") || line.endsWith("*/") || line.startsWith("--") || line.startsWith("#");
        }
        return false;
    }

    private void appendTail(byte[] buffer, int offset, int length) {
        // 只留最后 TAIL_BYTES 个字节：整份文件可能有几个 GB，不能为了看结尾把它留在内存里。
        int from = Math.max(offset, offset + length - TAIL_BYTES);
        for (int index = from; index < offset + length; index++) {
            int position = (tailStart + tailLength) % TAIL_BYTES;
            tail[position] = buffer[index];
            if (tailLength < TAIL_BYTES) tailLength++;
            else tailStart = (tailStart + 1) % TAIL_BYTES;
        }
    }

}
