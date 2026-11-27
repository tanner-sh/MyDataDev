package com.example.dbadmin.service.migration.artifact;

import com.example.dbadmin.service.migration.model.CanonicalSchema;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ArtifactChecksums;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ArtifactManifest;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ArtifactPartEntry;
import com.example.dbadmin.service.migration.model.CanonicalSchema.ArtifactTableEntry;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalProgramObject;
import com.example.dbadmin.service.migration.model.CanonicalSchema.CanonicalTable;
import com.example.dbadmin.service.migration.model.CanonicalSchema.TableChecksum;
import com.example.dbadmin.service.migration.verify.RowDigestAccumulator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * MyDataDev 标准迁移制品（{@code .mddpkg}，ZIP64 容器）高性能流式写出器（面向百 GB / 万表级大库）。
 *
 * <p>核心性能与稳定性设计：
 * <ul>
 *   <li><b>单遍全文件 SHA-256</b>：底层文件流直接套 {@link DigestOutputStream}，{@link #finish()} 零回读磁盘（百 GB 文件省掉整次 100 GB 顺序读）；</li>
 *   <li><b>空表零开销</b>：分片按首行懒开启，0 行表不产生无意义的空 {@code part-00001.mddbin} 条目；</li>
 *   <li><b>多表并发导出装配</b>：{@link #beginParallelTable} 支持多线程并发读源表 + 并行 GZIP 编码分片（小分片 ≤ 2 MB 走内存、大分片溢写临时文件，提交入 ZIP 后立刻删盘，百 GB 库临时空间恒定受控于 {@code concurrency × partMaxBytes}）；</li>
 *   <li><b>智能压缩</b>：ZIP 层对分片走 0 级存储，帧内文本/CLOB 走 GZIP、已压缩 JPEG/PNG/PDF/ZIP BLOB 原样直出，JVM 堆恒定 $O(1)$。</li>
 * </ul></p>
 */
public final class MddPackageWriter implements AutoCloseable {
    /** 并行导出模式下单个分片保留在堆内的上限（2 MB）；超过即自动溢写随用随删的临时文件。 */
    private static final int PARALLEL_PART_MEM_SPILL_BYTES = 2 * 1024 * 1024;

    private final Path finalPath;
    private final Path tempPath;
    private final Path spillDir;
    private final ObjectMapper mapper;
    private final CanonicalSchema schema;
    private final String generatorVersion;
    private final long partMaxBytes;
    private final long partMaxRows;
    private final MessageDigest fileDigest;
    private final ZipOutputStream zos;

    private final Map<String, String> entryChecksums = new LinkedHashMap<>();
    private final List<ArtifactTableEntry> tableEntries = new ArrayList<>();
    private final Map<String, TableChecksum> tableChecksums = new LinkedHashMap<>();
    private long totalRows;
    private long totalRawBytes;
    private boolean finished;
    private boolean closed;

    public MddPackageWriter(
            Path finalPath,
            ObjectMapper objectMapper,
            CanonicalSchema schema,
            String generatorVersion,
            long partMaxBytes,
            long partMaxRows
    ) throws IOException {
        this.finalPath = finalPath;
        Path parentDir = finalPath.toAbsolutePath().getParent();
        Files.createDirectories(parentDir);
        this.spillDir = parentDir;
        this.tempPath = finalPath.resolveSibling(finalPath.getFileName() + ".tmp");
        Files.deleteIfExists(tempPath);
        this.mapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.schema = schema;
        this.generatorVersion = generatorVersion == null ? "1.0.1" : generatorVersion;
        this.partMaxBytes = Math.max(4L * 1024 * 1024, partMaxBytes);
        this.partMaxRows = Math.max(1_000L, partMaxRows);
        this.fileDigest = RowDigestAccumulator.sha256();
        OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(tempPath), MddPackageFormat.IO_BUFFER_BYTES);
        this.zos = new ZipOutputStream(new DigestOutputStream(fileOut, fileDigest));
        writeInitialMetadata();
    }

    private void writeInitialMetadata() throws IOException {
        writeJsonEntry(MddPackageFormat.ENTRY_SCHEMA, schema);
        for (CanonicalProgramObject obj : schema.programObjects()) {
            if (obj.sourceSql() == null || obj.sourceSql().isBlank()) continue;
            String folder = obj.kind().toLowerCase(Locale.ROOT) + "s";
            String safeName = obj.name().replaceAll("[^a-zA-Z0-9_.-]", "_");
            writeBytesEntry("objects/" + folder + "/" + safeName + ".sql", obj.sourceSql().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 开启单线程直通 ZIP 的流式写入会话（零临时文件，适合单表或并发度 1 场景）。 */
    public TableWriter beginTable(CanonicalTable table, int temporalFspCap, String timezonePolicy) throws IOException {
        return new TableWriter(table, temporalFspCap, timezonePolicy, false);
    }

    /** 开启线程安全的并发导出写入会话（多线程并行读源表与并行压缩，分片就绪后串行装入 ZIP 并即刻清理临时文件）。 */
    public TableWriter beginParallelTable(CanonicalTable table, int temporalFspCap, String timezonePolicy) throws IOException {
        return new TableWriter(table, temporalFspCap, timezonePolicy, true);
    }

    /** 写入 {@code verify/checksums.json} 与 {@code manifest.json}，关闭 ZIP64 并原子落盘（零二次读盘算 SHA-256）。 */
    public synchronized WriteResult finish() throws IOException {
        if (finished) throw new IllegalStateException("制品已完成写入。");
        writeJsonEntry(MddPackageFormat.ENTRY_CHECKSUMS, new ArtifactChecksums(MddPackageFormat.FORMAT_VERSION, Map.copyOf(tableChecksums)));

        ArtifactManifest manifest = new ArtifactManifest(
                MddPackageFormat.FORMAT_VERSION,
                generatorVersion,
                Instant.now().toString(),
                schema.sourceDbType(),
                schema.sourceFamily(),
                schema.sourceVersion(),
                schema.sourceCharset(),
                schema.sourceSchema(),
                schema.snapshotToken(),
                tableEntries.size(),
                totalRows,
                totalRawBytes,
                List.copyOf(tableEntries),
                Map.copyOf(entryChecksums)
        );
        byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
        zos.setLevel(Deflater.DEFAULT_COMPRESSION);
        zos.putNextEntry(new ZipEntry(MddPackageFormat.ENTRY_MANIFEST));
        zos.write(manifestBytes);
        zos.closeEntry();

        zos.finish();
        zos.close();
        closed = true;
        finished = true;

        Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        long fileSize = Files.size(finalPath);
        String fileSha256 = HexFormat.of().formatHex(fileDigest.digest());
        return new WriteResult(finalPath, fileSize, fileSha256, manifest, Map.copyOf(tableChecksums));
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                zos.close();
            } finally {
                if (!finished) {
                    Files.deleteIfExists(tempPath);
                }
            }
        }
    }

    private synchronized void commitParallelPart(
            String entryPath,
            byte[] memBytes,
            Path spillFile,
            String shaHex
    ) throws IOException {
        try {
            if (closed) {
                throw new IOException("制品写入器已关闭，放弃提交分片：" + entryPath);
            }
            zos.setLevel(Deflater.NO_COMPRESSION);
            zos.putNextEntry(new ZipEntry(entryPath));
            if (memBytes != null) {
                zos.write(memBytes);
            } else if (spillFile != null) {
                byte[] buf = new byte[MddPackageFormat.IO_BUFFER_BYTES];
                try (InputStream in = Files.newInputStream(spillFile)) {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (n > 0) zos.write(buf, 0, n);
                    }
                }
            }
            zos.closeEntry();
            entryChecksums.put(entryPath, shaHex);
        } finally {
            if (spillFile != null) {
                Files.deleteIfExists(spillFile);
            }
        }
    }

    private synchronized void recordTableFinished(
            CanonicalTable table,
            long rows,
            long rawBytes,
            List<ArtifactPartEntry> parts,
            TableChecksum checksum
    ) {
        tableChecksums.put(table.tableId(), checksum);
        tableEntries.add(new ArtifactTableEntry(
                table.tableId(), table.name(), rows, rawBytes, parts.size(), List.copyOf(parts)
        ));
        totalRows += rows;
        totalRawBytes += rawBytes;
    }

    private void writeJsonEntry(String entryName, Object payload) throws IOException {
        writeBytesEntry(entryName, mapper.writeValueAsBytes(payload));
    }

    private void writeBytesEntry(String entryName, byte[] data) throws IOException {
        zos.setLevel(Deflater.DEFAULT_COMPRESSION);
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(data);
        zos.closeEntry();
        entryChecksums.put(entryName, HexFormat.of().formatHex(RowDigestAccumulator.sha256().digest(data)));
    }

    public record WriteResult(
            Path path,
            long fileSize,
            String fileSha256,
            ArtifactManifest manifest,
            Map<String, TableChecksum> checksums
    ) {
    }

    /** 单表流式分片写入器（支持直通 ZIP 与多线程分片溢写两种模式；0 行表不产生空分片）。 */
    public final class TableWriter implements AutoCloseable {
        private final CanonicalTable table;
        private final boolean parallelMode;
        private final RowDigestAccumulator accumulator;
        private final List<ArtifactPartEntry> parts = new ArrayList<>();

        private int partIndex = 0;
        private String currentEntryPath;
        private MessageDigest partDigest;
        private CountingOutputStream partCounter;
        private DataOutputStream partOut;
        private SpillableBufferOutputStream spillBuffer;
        private long partRows;
        private long tableRows;
        private long tableRawBytes;
        private boolean tableClosed;
        private boolean aborted;

        TableWriter(CanonicalTable table, int temporalFspCap, String timezonePolicy, boolean parallelMode) {
            this.table = table;
            this.parallelMode = parallelMode;
            List<String> colNames = table.columns().stream().map(CanonicalSchema.CanonicalColumn::name).toList();
            var colTypes = table.columns().stream().map(CanonicalSchema.CanonicalColumn::canonicalType).toList();
            this.accumulator = new RowDigestAccumulator(colNames, colTypes, temporalFspCap, timezonePolicy);
        }

        /** 取消/异常时标记放弃本表，避免 close() 向 ZIP 提交半成品分片，并立刻清理溢写临时文件。 */
        public void abort() {
            this.aborted = true;
            cleanupSpillQuietly();
            partOut = null;
            partCounter = null;
        }

        public void writeRow(List<CanonicalCell> cells) throws IOException {
            if (tableClosed || aborted) throw new IllegalStateException("表写入已关闭：" + table.name());
            if (partOut == null) {
                openNextPart();
            }
            partOut.writeByte(MddPackageFormat.ROW_PRESENT);
            for (CanonicalCell cell : cells) {
                writeCell(cell);
            }
            accumulator.recordRow(cells);
            partRows++;
            tableRows++;
            if (partRows >= partMaxRows || partCounter.bytesWritten >= partMaxBytes) {
                closeCurrentPart();
            }
        }

        public long tableRows() { return tableRows; }
        public long tableRawBytes() { return tableRawBytes + (partCounter == null ? 0 : partCounter.bytesWritten); }
        public int partCount() { return parts.size() + (partOut != null ? 1 : 0); }

        @Override
        public void close() throws IOException {
            if (tableClosed) return;
            tableClosed = true;
            if (aborted) {
                cleanupSpillQuietly();
                return;
            }
            try {
                closeCurrentPart();
            } catch (IOException error) {
                cleanupSpillQuietly();
                throw error;
            }
            TableChecksum checksum = accumulator.toTableChecksum(table.tableId(), table.name());
            recordTableFinished(table, tableRows, tableRawBytes, parts, checksum);
        }

        private void openNextPart() throws IOException {
            partIndex++;
            partRows = 0;
            currentEntryPath = String.format(Locale.ROOT, "data/%s/part-%05d.mddbin", table.tableId(), partIndex);
            partDigest = RowDigestAccumulator.sha256();
            OutputStream sink;
            if (parallelMode) {
                spillBuffer = new SpillableBufferOutputStream(spillDir, PARALLEL_PART_MEM_SPILL_BYTES);
                sink = spillBuffer;
            } else {
                zos.setLevel(Deflater.NO_COMPRESSION);
                zos.putNextEntry(new ZipEntry(currentEntryPath));
                sink = new NonClosingOutputStream(zos);
            }
            partCounter = new CountingOutputStream(new DigestOutputStream(sink, partDigest));
            partOut = new DataOutputStream(new BufferedOutputStream(partCounter, MddPackageFormat.IO_BUFFER_BYTES));
            partOut.write(MddPackageFormat.MAGIC);
            partOut.writeByte(MddPackageFormat.FORMAT_VERSION);
            partOut.writeShort(table.columns().size());
        }

        private void closeCurrentPart() throws IOException {
            if (partOut == null) return;
            partOut.writeByte(MddPackageFormat.ROW_EOF);
            partOut.writeLong(partRows);
            partOut.flush();
            long rawBytes = partCounter.bytesWritten;
            partOut.writeLong(rawBytes);
            partOut.flush();

            String sha = HexFormat.of().formatHex(partDigest.digest());
            if (parallelMode) {
                SpillableBufferOutputStream buf = spillBuffer;
                spillBuffer = null;
                partOut = null;
                partCounter = null;
                try {
                    buf.close();
                } catch (IOException closeErr) {
                    try { buf.abortAndDelete(); } catch (IOException ignored) { }
                    throw closeErr;
                }
                commitParallelPart(currentEntryPath, buf.memoryBytes(), buf.spillFile(), sha);
            } else {
                zos.closeEntry();
                entryChecksums.put(currentEntryPath, sha);
                partOut = null;
                partCounter = null;
            }
            parts.add(new ArtifactPartEntry(partIndex, currentEntryPath, partRows, rawBytes, sha));
            tableRawBytes += rawBytes;
        }

        private void cleanupSpillQuietly() {
            if (spillBuffer != null) {
                try { spillBuffer.abortAndDelete(); } catch (IOException ignored) { }
                spillBuffer = null;
            }
        }

        private void writeCell(CanonicalCell cell) throws IOException {
            if (cell == null || cell.isNull()) {
                partOut.writeByte(MddPackageFormat.TAG_NULL);
                return;
            }
            switch (cell.kind()) {
                case NULL -> partOut.writeByte(MddPackageFormat.TAG_NULL);
                case BOOLEAN -> {
                    partOut.writeByte(MddPackageFormat.TAG_BOOLEAN);
                    partOut.writeBoolean(cell.asBoolean());
                }
                case INT64 -> {
                    partOut.writeByte(MddPackageFormat.TAG_INT64);
                    partOut.writeLong(cell.asInt64());
                }
                case FLOAT64 -> {
                    partOut.writeByte(MddPackageFormat.TAG_FLOAT64);
                    partOut.writeDouble(cell.asFloat64());
                }
                case DECIMAL -> {
                    partOut.writeByte(MddPackageFormat.TAG_DECIMAL);
                    byte[] utf8 = cell.asDecimal().toPlainString().getBytes(StandardCharsets.UTF_8);
                    partOut.writeInt(utf8.length);
                    partOut.write(utf8);
                }
                case STRING -> {
                    byte[] utf8 = cell.asString().getBytes(StandardCharsets.UTF_8);
                    if (utf8.length <= MddPackageFormat.INLINE_LOB_MAX_BYTES) {
                        partOut.writeByte(MddPackageFormat.TAG_STRING);
                        partOut.writeInt(utf8.length);
                        partOut.write(utf8);
                    } else {
                        writeLargeBytesAsLob(MddPackageFormat.TAG_STREAM_CLOB, utf8, true, cell);
                    }
                }
                case DATE -> {
                    LocalDate d = cell.asDate();
                    partOut.writeByte(MddPackageFormat.TAG_DATE);
                    partOut.writeInt(d.getYear());
                    partOut.writeByte(d.getMonthValue());
                    partOut.writeByte(d.getDayOfMonth());
                }
                case TIME -> {
                    LocalTime t = cell.asTime();
                    partOut.writeByte(MddPackageFormat.TAG_TIME);
                    partOut.writeByte(t.getHour());
                    partOut.writeByte(t.getMinute());
                    partOut.writeByte(t.getSecond());
                    partOut.writeInt(t.getNano());
                }
                case DATETIME -> {
                    LocalDateTime dt = cell.asDateTime();
                    partOut.writeByte(MddPackageFormat.TAG_DATETIME);
                    partOut.writeInt(dt.getYear());
                    partOut.writeByte(dt.getMonthValue());
                    partOut.writeByte(dt.getDayOfMonth());
                    partOut.writeByte(dt.getHour());
                    partOut.writeByte(dt.getMinute());
                    partOut.writeByte(dt.getSecond());
                    partOut.writeInt(dt.getNano());
                }
                case TIMESTAMP_TZ -> {
                    OffsetDateTime odt = cell.asTimestampTz();
                    partOut.writeByte(MddPackageFormat.TAG_TIMESTAMP_TZ);
                    partOut.writeInt(odt.getYear());
                    partOut.writeByte(odt.getMonthValue());
                    partOut.writeByte(odt.getDayOfMonth());
                    partOut.writeByte(odt.getHour());
                    partOut.writeByte(odt.getMinute());
                    partOut.writeByte(odt.getSecond());
                    partOut.writeInt(odt.getNano());
                    partOut.writeInt(odt.getOffset().getTotalSeconds());
                }
                case BYTES -> {
                    byte[] bytes = cell.asBytes();
                    if (bytes.length <= MddPackageFormat.INLINE_LOB_MAX_BYTES) {
                        partOut.writeByte(MddPackageFormat.TAG_BYTES);
                        partOut.writeInt(bytes.length);
                        partOut.write(bytes);
                        cell.recordLobDigest(RowDigestAccumulator.sha256().digest(bytes), bytes.length);
                    } else {
                        boolean compress = MddPackageFormat.shouldCompressBinary(bytes, bytes.length);
                        writeLargeBytesAsLob(MddPackageFormat.TAG_STREAM_BLOB, bytes, compress, cell);
                    }
                }
                case STREAM_BLOB -> writeStreamBlob(cell);
                case STREAM_CLOB -> writeStreamClob(cell);
            }
        }

        private void writeLargeBytesAsLob(byte tag, byte[] raw, boolean compress, CanonicalCell cell) throws IOException {
            partOut.writeByte(tag);
            partOut.writeByte(compress ? MddPackageFormat.CODEC_GZIP : MddPackageFormat.CODEC_RAW);
            partOut.writeLong(raw.length);
            byte[] sha = RowDigestAccumulator.sha256().digest(raw);
            try (ChunkedFrameOutputStream chunked = new ChunkedFrameOutputStream(partOut)) {
                if (compress) {
                    try (GZIPOutputStream gz = new GZIPOutputStream(chunked, MddPackageFormat.IO_BUFFER_BYTES)) {
                        gz.write(raw);
                    }
                } else {
                    chunked.write(raw);
                }
            }
            partOut.writeLong(raw.length);
            partOut.write(sha);
            cell.recordLobDigest(sha, raw.length);
        }

        private void writeStreamBlob(CanonicalCell cell) throws IOException {
            byte[] probe = new byte[4096];
            int probeLen = 0;
            MessageDigest md = RowDigestAccumulator.sha256();
            long totalBytes = 0;
            try (InputStream in = cell.asBlobStream()) {
                int r;
                while (probeLen < probe.length && (r = in.read(probe, probeLen, probe.length - probeLen)) >= 0) {
                    probeLen += r;
                }
                boolean compress = MddPackageFormat.shouldCompressBinary(probe, probeLen);
                partOut.writeByte(MddPackageFormat.TAG_STREAM_BLOB);
                partOut.writeByte(compress ? MddPackageFormat.CODEC_GZIP : MddPackageFormat.CODEC_RAW);
                partOut.writeLong(cell.knownLength());

                byte[] buf = new byte[MddPackageFormat.IO_BUFFER_BYTES];
                try (ChunkedFrameOutputStream chunked = new ChunkedFrameOutputStream(partOut)) {
                    OutputStream sink = compress ? new GZIPOutputStream(chunked, MddPackageFormat.IO_BUFFER_BYTES) : chunked;
                    if (probeLen > 0) {
                        md.update(probe, 0, probeLen);
                        sink.write(probe, 0, probeLen);
                        totalBytes += probeLen;
                    }
                    while ((r = in.read(buf)) >= 0) {
                        if (r == 0) continue;
                        md.update(buf, 0, r);
                        sink.write(buf, 0, r);
                        totalBytes += r;
                    }
                    if (sink instanceof GZIPOutputStream gz) {
                        gz.finish();
                    }
                }
            }
            byte[] sha = md.digest();
            partOut.writeLong(totalBytes);
            partOut.write(sha);
            cell.recordLobDigest(sha, totalBytes);
        }

        private void writeStreamClob(CanonicalCell cell) throws IOException {
            partOut.writeByte(MddPackageFormat.TAG_STREAM_CLOB);
            partOut.writeByte(MddPackageFormat.CODEC_GZIP);
            partOut.writeLong(-1L);

            MessageDigest md = RowDigestAccumulator.sha256();
            long totalBytes = 0;
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            char[] cbuf = new char[32 * 1024];
            ByteBuffer bbuf = ByteBuffer.allocate(128 * 1024);

            try (Reader reader = cell.asClobStream();
                 ChunkedFrameOutputStream chunked = new ChunkedFrameOutputStream(partOut);
                 GZIPOutputStream gz = new GZIPOutputStream(chunked, MddPackageFormat.IO_BUFFER_BYTES)) {
                int read;
                while ((read = reader.read(cbuf)) >= 0) {
                    if (read == 0) continue;
                    CharBuffer inBuf = CharBuffer.wrap(cbuf, 0, read);
                    while (inBuf.hasRemaining()) {
                        bbuf.clear();
                        CoderResult cr = encoder.encode(inBuf, bbuf, false);
                        bbuf.flip();
                        int len = bbuf.remaining();
                        if (len > 0) {
                            md.update(bbuf.array(), 0, len);
                            gz.write(bbuf.array(), 0, len);
                            totalBytes += len;
                        }
                        if (cr.isError()) cr.throwException();
                    }
                }
                CharBuffer empty = CharBuffer.allocate(0);
                while (true) {
                    bbuf.clear();
                    CoderResult cr = encoder.encode(empty, bbuf, true);
                    bbuf.flip();
                    int len = bbuf.remaining();
                    if (len > 0) {
                        md.update(bbuf.array(), 0, len);
                        gz.write(bbuf.array(), 0, len);
                        totalBytes += len;
                    }
                    if (!cr.isOverflow()) break;
                }
                bbuf.clear();
                encoder.flush(bbuf);
                bbuf.flip();
                if (bbuf.hasRemaining()) {
                    int len = bbuf.remaining();
                    md.update(bbuf.array(), 0, len);
                    gz.write(bbuf.array(), 0, len);
                    totalBytes += len;
                }
                gz.finish();
            }
            byte[] sha = md.digest();
            partOut.writeLong(totalBytes);
            partOut.write(sha);
            cell.recordLobDigest(sha, totalBytes);
        }
    }

    /** 小分片（≤ 阈值）纯内存零盘 IO，大分片自动溢写临时文件，提交或异常中止时立即清理。 */
    private static final class SpillableBufferOutputStream extends OutputStream {
        private final Path spillDir;
        private final int memoryLimit;
        private ByteArrayOutputStream mem = new ByteArrayOutputStream(16 * 1024);
        private Path spillFile;
        private OutputStream fileOut;
        private boolean closed;

        SpillableBufferOutputStream(Path spillDir, int memoryLimit) {
            this.spillDir = spillDir;
            this.memoryLimit = memoryLimit;
        }

        @Override
        public void write(int b) throws IOException {
            ensureCapacity(1);
            if (fileOut != null) fileOut.write(b);
            else mem.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ensureCapacity(len);
            if (fileOut != null) fileOut.write(b, off, len);
            else mem.write(b, off, len);
        }

        private void ensureCapacity(int incoming) throws IOException {
            if (fileOut == null && mem.size() + incoming > memoryLimit) {
                spillFile = Files.createTempFile(spillDir, "mdd-part-spill-", ".tmp");
                fileOut = new BufferedOutputStream(Files.newOutputStream(spillFile), MddPackageFormat.IO_BUFFER_BYTES);
                mem.writeTo(fileOut);
                mem = null;
            }
        }

        byte[] memoryBytes() {
            return mem == null ? null : mem.toByteArray();
        }

        Path spillFile() {
            return spillFile;
        }

        void abortAndDelete() throws IOException {
            close();
            if (spillFile != null) {
                Files.deleteIfExists(spillFile);
                spillFile = null;
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            if (fileOut != null) {
                fileOut.close();
                fileOut = null;
            }
        }
    }

    /** 将流式载荷分块写成 {@code [chunkLen: int32][bytes] ... [0]}，不关闭底层 {@link DataOutputStream}。 */
    private static final class ChunkedFrameOutputStream extends OutputStream {
        private final DataOutputStream out;
        private final byte[] buffer = new byte[MddPackageFormat.IO_BUFFER_BYTES];
        private int count;
        private boolean closed;

        ChunkedFrameOutputStream(DataOutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            buffer[count++] = (byte) b;
            if (count == buffer.length) flushChunk();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int space = buffer.length - count;
                int copy = Math.min(space, len);
                System.arraycopy(b, off, buffer, count, copy);
                count += copy;
                off += copy;
                len -= copy;
                if (count == buffer.length) flushChunk();
            }
        }

        private void flushChunk() throws IOException {
            if (count > 0) {
                out.writeInt(count);
                out.write(buffer, 0, count);
                count = 0;
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            flushChunk();
            out.writeInt(0);
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long bytesWritten;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            bytesWritten++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            bytesWritten += len;
        }
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
