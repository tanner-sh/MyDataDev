package com.example.dbadmin.service;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class SqlFileStatementReader {
    private SqlFileStatementReader() {
    }

    static Charset detectCharset(Path path) throws Exception {
        Charset bom = detectBomCharset(path);
        if (bom != null) return bom;
        if (valid(path, StandardCharsets.UTF_8)) return StandardCharsets.UTF_8;
        Charset gb18030 = Charset.forName("GB18030");
        if (valid(path, gb18030)) return gb18030;
        throw new IllegalArgumentException("无法识别 SQL 文件编码；仅支持带 BOM 的 UTF 编码、UTF-8 和 GB18030。");
    }

    static Charset detectBomCharset(Path path) throws IOException {
        byte[] prefix = new byte[3];
        int read;
        try (InputStream input = Files.newInputStream(path)) {
            read = input.read(prefix);
        }
        if (read >= 3 && (prefix[0] & 0xff) == 0xef && (prefix[1] & 0xff) == 0xbb && (prefix[2] & 0xff) == 0xbf) {
            return StandardCharsets.UTF_8;
        }
        if (read >= 2 && (prefix[0] & 0xff) == 0xff && (prefix[1] & 0xff) == 0xfe) {
            return StandardCharsets.UTF_16LE;
        }
        if (read >= 2 && (prefix[0] & 0xff) == 0xfe && (prefix[1] & 0xff) == 0xff) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    private static boolean valid(Path path, Charset charset) throws IOException {
        CharsetDecoder decoder = decoder(charset);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = new byte[128 * 1024];
            ByteBuffer pending = ByteBuffer.allocate(bytes.length + 8);
            CharBuffer chars = CharBuffer.allocate(bytes.length);
            int count;
            while ((count = input.read(bytes)) >= 0) {
                if (count == 0) continue;
                pending.put(bytes, 0, count).flip();
                while (true) {
                    var result = decoder.decode(pending, chars, false);
                    if (result.isError()) result.throwException();
                    chars.clear();
                    if (result.isUnderflow()) break;
                }
                pending.compact();
            }
            pending.flip();
            var result = decoder.decode(pending, chars, true);
            if (result.isError()) result.throwException();
            result = decoder.flush(chars);
            if (result.isError()) result.throwException();
            return true;
        } catch (CharacterCodingException error) {
            return false;
        }
    }

    static void read(Path path, Charset charset, String dbType, int maxStatementChars,
                     StatementConsumer consumer, ProgressConsumer progress) throws Exception {
        readUnits(path, charset, dbType, maxStatementChars, unit -> consumer.accept(unit.index(), unit.sql()), progress);
    }

    static void readUnits(Path path, Charset charset, String dbType, int maxStatementChars,
                          SqlScriptParser.UnitConsumer consumer, ProgressConsumer progress) throws Exception {
        try (CountingInputStream input = new CountingInputStream(Files.newInputStream(path));
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder(charset)), 128 * 1024)) {
            long[] lastProgress = {0};
            SqlScriptParser.read(reader, dbType, maxStatementChars, unit -> {
                consumer.accept(unit);
                if (input.count() - lastProgress[0] >= 4L * 1024 * 1024) {
                    lastProgress[0] = input.count();
                    progress.accept(lastProgress[0]);
                }
            });
            progress.accept(input.count());
        }
    }

    private static CharsetDecoder decoder(Charset charset) {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;
        private CountingInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException { int value = super.read(); if (value >= 0) count++; return value; }
        @Override public int read(byte[] value, int offset, int length) throws IOException {
            int read = super.read(value, offset, length); if (read > 0) count += read; return read;
        }
        private long count() { return count; }
    }

    @FunctionalInterface interface StatementConsumer { void accept(long index, String sql) throws Exception; }
    @FunctionalInterface interface ProgressConsumer { void accept(long processedBytes) throws Exception; }
}
