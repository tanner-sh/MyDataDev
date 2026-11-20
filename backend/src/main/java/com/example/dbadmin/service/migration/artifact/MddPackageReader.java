package com.example.dbadmin.service.migration.artifact;

import com.example.dbadmin.service.migration.model.CanonicalSchema;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ArtifactChecksums;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ArtifactManifest;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ArtifactPartEntry;
import com.example.dbadmin.service.migration.verify.RowDigestAccumulator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MyDataDev 标准迁移制品（{@code .mddpkg}，ZIP64 容器）随机与流式读取器。
 *
 * <p>基于 {@link ZipFile} 支持：
 * <ul>
 *   <li>毫秒级随机读取 {@code manifest.json} / {@code schema.json} / {@code verify/checksums.json}，无需扫描 10 GB 数据；</li>
 *   <li>多线程按 {@code part-NNNNN.mddbin} 独立并发读取，天然支持分片级断点续传；</li>
 *   <li>大 LOB（> 2 MB）通过自动清理临时文件承载并校验每格 SHA-256，既支持一行多列超大 BLOB，又保证 JVM 堆恒定可控且零临时文件泄露。</li>
 * </ul></p>
 */
public final class MddPackageReader implements AutoCloseable {
    /** 单个 LOB 在内存保持的最大字节数；超过此值自动溢出到随 cell.close() 立即销毁的临时文件，保护堆内存。 */
    private static final int LOB_MEMORY_SPILL_BYTES = 2 * 1024 * 1024;

    private final Path path;
    private final ZipFile zipFile;
    private final ObjectMapper mapper;

    public MddPackageReader(Path path, ObjectMapper mapper) throws IOException {
        this.path = path;
        this.zipFile = new ZipFile(path.toFile(), StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    public Path path() { return path; }

    public ArtifactManifest readManifest() throws IOException {
        return readJson(MddPackageFormat.ENTRY_MANIFEST, ArtifactManifest.class);
    }

    public CanonicalSchema readSchema() throws IOException {
        return readJson(MddPackageFormat.ENTRY_SCHEMA, CanonicalSchema.class);
    }

    public ArtifactChecksums readChecksums() throws IOException {
        return readJson(MddPackageFormat.ENTRY_CHECKSUMS, ArtifactChecksums.class);
    }

    /** 校验制品内各条目（schema/checksums/所有数据分片）的 SHA-256 与 manifest 一致。 */
    public void verifyIntegrity() throws IOException {
        ArtifactManifest manifest = readManifest();
        if (manifest.entryChecksums() == null) return;
        byte[] buf = new byte[MddPackageFormat.IO_BUFFER_BYTES];
        for (var entry : manifest.entryChecksums().entrySet()) {
            ZipEntry ze = zipFile.getEntry(entry.getKey());
            if (ze == null) {
                throw new IOException("制品缺少声明的条目：" + entry.getKey());
            }
            MessageDigest md = RowDigestAccumulator.sha256();
            try (InputStream in = zipFile.getInputStream(ze)) {
                int r;
                while ((r = in.read(buf)) >= 0) {
                    if (r > 0) md.update(buf, 0, r);
                }
            }
            String actual = HexFormat.of().formatHex(md.digest());
            if (!actual.equalsIgnoreCase(entry.getValue())) {
                throw new IOException("制品条目校验失败（可能损坏或被截断）：" + entry.getKey());
            }
        }
    }

    public PartCursor openPart(ArtifactPartEntry part) throws IOException {
        ZipEntry entry = zipFile.getEntry(part.entryPath());
        if (entry == null) {
            throw new IOException("制品中找不到数据分片：" + part.entryPath());
        }
        return new PartCursor(zipFile.getInputStream(entry), part.entryPath());
    }

    private <T> T readJson(String entryName, Class<T> type) throws IOException {
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) {
            throw new IOException("制品缺少必需元数据条目：" + entryName);
        }
        try (InputStream in = zipFile.getInputStream(entry)) {
            return mapper.readValue(in, type);
        }
    }

    @Override
    public void close() throws IOException {
        zipFile.close();
    }

    /** 单个 {@code .mddbin} 分片的行流游标。调用方处理完每行后必须关闭行内 {@link CanonicalCell}。 */
    public static final class PartCursor implements AutoCloseable {
        private final DataInputStream in;
        private final String entryPath;
        private final int columnCount;
        private boolean eof;
        private long rowsRead;

        PartCursor(InputStream rawIn, String entryPath) throws IOException {
            this.entryPath = entryPath;
            this.in = new DataInputStream(new BufferedInputStream(rawIn, MddPackageFormat.IO_BUFFER_BYTES));
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!Arrays.equals(magic, MddPackageFormat.MAGIC)) {
                throw new IOException("非法分片文件头：" + entryPath);
            }
            int version = in.readUnsignedByte();
            if (version != MddPackageFormat.FORMAT_VERSION) {
                throw new IOException("不支持的分片协议版本 " + version + "：" + entryPath);
            }
            this.columnCount = in.readUnsignedShort();
        }

        public int columnCount() { return columnCount; }
        public long rowsRead() { return rowsRead; }

        /** 读取下一行；到达分片末尾返回 {@code null}。 */
        public List<CanonicalCell> nextRow() throws IOException {
            if (eof) return null;
            int marker = in.readUnsignedByte();
            if (marker == MddPackageFormat.ROW_EOF) {
                eof = true;
                long expectedRows = in.readLong();
                in.readLong(); // rawBytes footer
                if (expectedRows != rowsRead) {
                    throw new IOException("分片 " + entryPath + " 行数校验不一致：声明 " + expectedRows + " 行，实读 " + rowsRead + " 行");
                }
                return null;
            }
            if (marker != MddPackageFormat.ROW_PRESENT) {
                throw new IOException("分片 " + entryPath + " 遇到非法行标记：" + marker);
            }
            List<CanonicalCell> row = new ArrayList<>(columnCount);
            try {
                for (int i = 0; i < columnCount; i++) {
                    row.add(readCell());
                }
            } catch (IOException | RuntimeException error) {
                for (CanonicalCell c : row) {
                    try { c.close(); } catch (IOException ignored) { }
                }
                throw error;
            }
            rowsRead++;
            return row;
        }

        private CanonicalCell readCell() throws IOException {
            byte tag = in.readByte();
            return switch (tag) {
                case MddPackageFormat.TAG_NULL -> CanonicalCell.ofNull();
                case MddPackageFormat.TAG_BOOLEAN -> CanonicalCell.ofBoolean(in.readBoolean());
                case MddPackageFormat.TAG_INT64 -> CanonicalCell.ofInt64(in.readLong());
                case MddPackageFormat.TAG_FLOAT64 -> CanonicalCell.ofFloat64(in.readDouble());
                case MddPackageFormat.TAG_DECIMAL -> {
                    byte[] bytes = readByteArray();
                    yield CanonicalCell.ofDecimal(new BigDecimal(new String(bytes, StandardCharsets.UTF_8)));
                }
                case MddPackageFormat.TAG_STRING -> {
                    byte[] bytes = readByteArray();
                    yield CanonicalCell.ofString(new String(bytes, StandardCharsets.UTF_8));
                }
                case MddPackageFormat.TAG_DATE -> {
                    int year = in.readInt();
                    int month = in.readUnsignedByte();
                    int day = in.readUnsignedByte();
                    yield CanonicalCell.ofDate(LocalDate.of(year, month, day));
                }
                case MddPackageFormat.TAG_TIME -> {
                    int h = in.readUnsignedByte();
                    int m = in.readUnsignedByte();
                    int s = in.readUnsignedByte();
                    int nano = in.readInt();
                    yield CanonicalCell.ofTime(LocalTime.of(h, m, s, nano));
                }
                case MddPackageFormat.TAG_DATETIME -> {
                    int year = in.readInt();
                    int month = in.readUnsignedByte();
                    int day = in.readUnsignedByte();
                    int h = in.readUnsignedByte();
                    int m = in.readUnsignedByte();
                    int s = in.readUnsignedByte();
                    int nano = in.readInt();
                    yield CanonicalCell.ofDateTime(LocalDateTime.of(year, month, day, h, m, s, nano));
                }
                case MddPackageFormat.TAG_TIMESTAMP_TZ -> {
                    int year = in.readInt();
                    int month = in.readUnsignedByte();
                    int day = in.readUnsignedByte();
                    int h = in.readUnsignedByte();
                    int m = in.readUnsignedByte();
                    int s = in.readUnsignedByte();
                    int nano = in.readInt();
                    int offsetSec = in.readInt();
                    yield CanonicalCell.ofTimestampTz(OffsetDateTime.of(
                            LocalDateTime.of(year, month, day, h, m, s, nano),
                            ZoneOffset.ofTotalSeconds(offsetSec)
                    ));
                }
                case MddPackageFormat.TAG_BYTES -> {
                    byte[] raw = readByteArray();
                    CanonicalCell cell = CanonicalCell.ofBytes(raw);
                    cell.recordLobDigest(RowDigestAccumulator.sha256().digest(raw), raw.length);
                    yield cell;
                }
                case MddPackageFormat.TAG_STREAM_BLOB -> readLobCell(false);
                case MddPackageFormat.TAG_STREAM_CLOB -> readLobCell(true);
                default -> throw new IOException("分片 " + entryPath + " 遇到未知列类型标记：" + tag);
            };
        }

        private byte[] readByteArray() throws IOException {
            int len = in.readInt();
            if (len < 0 || len > 128 * 1024 * 1024) {
                throw new IOException("非法内联单元格长度：" + len);
            }
            byte[] buf = new byte[len];
            in.readFully(buf);
            return buf;
        }

        /**
         * 读取流式 LOB 帧并校验单元格级 SHA-256。
         * ≤ 2 MB 保持在堆内免落盘；> 2 MB 自动溢出到随 {@link CanonicalCell#close()} 销毁的临时文件。
         */
        private CanonicalCell readLobCell(boolean isClob) throws IOException {
            byte codec = in.readByte();
            in.readLong(); // knownLength hint

            ChunkedFrameInputStream chunkStream = new ChunkedFrameInputStream(in);
            InputStream decoded = codec == MddPackageFormat.CODEC_GZIP
                    ? new GZIPInputStream(chunkStream, MddPackageFormat.IO_BUFFER_BYTES)
                    : chunkStream;

            MessageDigest md = RowDigestAccumulator.sha256();
            ByteArrayOutputStream memBuf = new ByteArrayOutputStream(Math.min(64 * 1024, LOB_MEMORY_SPILL_BYTES));
            Path spillFile = null;
            OutputStream spillOut = null;
            long totalRead = 0;

            try {
                byte[] buf = new byte[MddPackageFormat.IO_BUFFER_BYTES];
                int n;
                while ((n = decoded.read(buf)) >= 0) {
                    if (n == 0) continue;
                    md.update(buf, 0, n);
                    totalRead += n;
                    if (spillOut != null) {
                        spillOut.write(buf, 0, n);
                    } else if (memBuf.size() + n <= LOB_MEMORY_SPILL_BYTES) {
                        memBuf.write(buf, 0, n);
                    } else {
                        spillFile = Files.createTempFile("mdd-lob-", ".bin");
                        spillOut = Files.newOutputStream(spillFile);
                        memBuf.writeTo(spillOut);
                        memBuf.reset();
                        spillOut.write(buf, 0, n);
                    }
                }
                chunkStream.drainToTerminator();
                if (spillOut != null) {
                    spillOut.close();
                    spillOut = null;
                }
            } catch (IOException | RuntimeException error) {
                if (spillOut != null) {
                    try { spillOut.close(); } catch (IOException ignored) { }
                }
                if (spillFile != null) Files.deleteIfExists(spillFile);
                throw error;
            }

            long expectedBytes = in.readLong();
            byte[] expectedSha = new byte[32];
            in.readFully(expectedSha);
            byte[] actualSha = md.digest();

            if (totalRead != expectedBytes || !Arrays.equals(expectedSha, actualSha)) {
                if (spillFile != null) Files.deleteIfExists(spillFile);
                throw new IOException("分片 " + entryPath + " 中大对象单元格 SHA-256 校验失败（可能数据损坏）");
            }

            if (spillFile == null) {
                byte[] data = memBuf.toByteArray();
                CanonicalCell cell = isClob
                        ? CanonicalCell.ofString(new String(data, StandardCharsets.UTF_8))
                        : CanonicalCell.ofBytes(data);
                cell.recordLobDigest(actualSha, totalRead);
                return cell;
            }
            if (isClob) {
                InputStream fileIn = new DeleteOnCloseInputStream(spillFile);
                CanonicalCell cell = CanonicalCell.ofClobStream(new InputStreamReader(fileIn, StandardCharsets.UTF_8), -1L);
                cell.recordLobDigest(actualSha, totalRead);
                return cell;
            } else {
                CanonicalCell cell = CanonicalCell.ofBlobStream(new DeleteOnCloseInputStream(spillFile), totalRead);
                cell.recordLobDigest(actualSha, totalRead);
                return cell;
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static final class ChunkedFrameInputStream extends InputStream {
        private final DataInputStream in;
        private int remainingInChunk;
        private boolean hitTerminator;

        ChunkedFrameInputStream(DataInputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int r = read(one, 0, 1);
            return r < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (hitTerminator) return -1;
            if (remainingInChunk == 0) {
                int nextLen = in.readInt();
                if (nextLen == 0) {
                    hitTerminator = true;
                    return -1;
                }
                if (nextLen < 0) throw new IOException("非法分块长度：" + nextLen);
                remainingInChunk = nextLen;
            }
            int toRead = Math.min(len, remainingInChunk);
            int r = in.read(b, off, toRead);
            if (r < 0) throw new EOFException("LOB 分块提前结束");
            remainingInChunk -= r;
            return r;
        }

        void drainToTerminator() throws IOException {
            if (hitTerminator) return;
            byte[] scratch = new byte[8192];
            while (read(scratch, 0, scratch.length) >= 0) {
                // consume remainder
            }
        }
    }

    private static final class DeleteOnCloseInputStream extends FilterInputStream {
        private final Path file;
        private boolean deleted;

        DeleteOnCloseInputStream(Path file) throws IOException {
            super(new BufferedInputStream(Files.newInputStream(file), MddPackageFormat.IO_BUFFER_BYTES));
            this.file = file;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (!deleted) {
                    deleted = true;
                    Files.deleteIfExists(file);
                }
            }
        }
    }
}
