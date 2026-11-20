package com.example.dbadmin.service.migration;

import com.example.dbadmin.service.migration.artifact.CanonicalCell;
import com.example.dbadmin.service.migration.artifact.MddPackageReader;
import com.example.dbadmin.service.migration.artifact.MddPackageWriter;
import com.example.dbadmin.service.migration.model.CanonicalSchema;
import com.example.dbadmin.service.migration.model.CanonicalSchema.*;
import com.example.dbadmin.service.migration.model.CanonicalTypeKind;
import com.example.dbadmin.service.migration.verify.RowDigestAccumulator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MddPackageAndDigestTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void multisetHashIsOrderIndependentDetectsDuplicatesAndNormalizesCrossDbRepresentations() {
        List<String> cols = List.of("id", "amount", "ts", "bin");
        byte[] blobA = "binary-payload-alpha".getBytes(StandardCharsets.UTF_8);
        byte[] blobB = "binary-payload-beta".getBytes(StandardCharsets.UTF_8);

        List<CanonicalCell> row1Src = List.of(
                CanonicalCell.ofInt64(1),
                CanonicalCell.ofDecimal(new BigDecimal("100.5000")),
                CanonicalCell.ofDateTime(LocalDateTime.of(2026, 11, 19, 12, 30, 45, 0)),
                CanonicalCell.ofBytes(blobA)
        );
        List<CanonicalCell> row1Tgt = List.of(
                CanonicalCell.ofInt64(1),
                CanonicalCell.ofDecimal(new BigDecimal("100.5")),
                CanonicalCell.ofDateTime(LocalDateTime.of(2026, 11, 19, 12, 30, 45, 0)),
                CanonicalCell.ofBytes(blobA)
        );
        List<CanonicalCell> row2 = List.of(
                CanonicalCell.ofInt64(2),
                CanonicalCell.ofDecimal(new BigDecimal("0.00")),
                CanonicalCell.ofDateTime(LocalDateTime.of(2026, 11, 19, 18, 0, 0, 123_000_000)),
                CanonicalCell.ofBytes(blobB)
        );

        RowDigestAccumulator accOrderA = new RowDigestAccumulator(cols, 6, "UTC");
        accOrderA.recordRow(row1Src);
        accOrderA.recordRow(row2);

        RowDigestAccumulator accOrderB = new RowDigestAccumulator(cols, 6, "UTC");
        accOrderB.recordRow(row2);
        accOrderB.recordRow(row1Tgt);

        assertThat(accOrderA.multisetHashHex()).isEqualTo(accOrderB.multisetHashHex());

        // 并行分片归并与单遍扫描结果逐位一致
        RowDigestAccumulator part1 = new RowDigestAccumulator(cols, 6, "UTC");
        part1.recordRow(row1Src);
        RowDigestAccumulator part2 = new RowDigestAccumulator(cols, 6, "UTC");
        part2.recordRow(row2);
        part1.merge(part2);
        assertThat(part1.multisetHashHex()).isEqualTo(accOrderA.multisetHashHex());

        // 重复行不会像 XOR 那样抵消成 0
        RowDigestAccumulator dup = new RowDigestAccumulator(cols, 6, "UTC");
        dup.recordRow(row2);
        dup.recordRow(row2);
        RowDigestAccumulator empty = new RowDigestAccumulator(cols, 6, "UTC");
        assertThat(dup.multisetHashHex()).isNotEqualTo(empty.multisetHashHex());
    }

    @Test
    void canonicalJsonDigestIgnoresKeyOrderAndWhitespace() {
        List<String> cols = List.of("id", "meta");
        List<CanonicalTypeKind> types = List.of(CanonicalTypeKind.INT64, CanonicalTypeKind.JSON);

        RowDigestAccumulator pgAcc = new RowDigestAccumulator(cols, types, 6, "UTC");
        pgAcc.recordRow(List.of(
                CanonicalCell.ofInt64(1L),
                CanonicalCell.ofString("{\"tags\": [\"a\", \"b\"], \"env\": \"docker\", \"nested\": {\"z\": 1, \"a\": 2}}")
        ));

        RowDigestAccumulator mysqlAcc = new RowDigestAccumulator(cols, types, 6, "UTC");
        mysqlAcc.recordRow(List.of(
                CanonicalCell.ofInt64(1L),
                CanonicalCell.ofString("{\"env\":\"docker\",\"nested\":{\"a\":2,\"z\":1},\"tags\":[\"a\",\"b\"]}")
        ));

        assertThat(pgAcc.multisetHashHex()).isEqualTo(mysqlAcc.multisetHashHex());
        assertThat(pgAcc.toTableChecksum("t1", "t").columnAggregates().get("meta").lobByteSum())
                .isEqualTo(mysqlAcc.toTableChecksum("t1", "t").columnAggregates().get("meta").lobByteSum());
    }

    @Test
    void packageRoundTripHandlesMultiMbBlobsAndClobsAcrossMultipleParts(@TempDir Path tempDir) throws Exception {
        // 构造 3 MB 不可压缩高熵 BLOB（伪 JPEG 头）+ 3 MB 高压缩率中文 CLOB，测试跨 2 MB 阈值自动溢写与零损坏还原
        byte[] largeBlob = new byte[3 * 1024 * 1024 + 137];
        largeBlob[0] = (byte) 0xFF;
        largeBlob[1] = (byte) 0xD8;
        largeBlob[2] = (byte) 0xFF;
        new Random(42L).nextBytes(largeBlob);
        largeBlob[0] = (byte) 0xFF;
        largeBlob[1] = (byte) 0xD8;
        largeBlob[2] = (byte) 0xFF;

        StringBuilder clobBuilder = new StringBuilder(3 * 1024 * 1024);
        String chunk = "MyDataDev异构数据库转换测试-包含中文与特殊符号\\'\"\n\t";
        while (clobBuilder.length() < 1_200_000) clobBuilder.append(chunk);
        String largeClob = clobBuilder.toString();

        CanonicalTable table = new CanonicalTable(
                "t0001_media", "MEDIA_FILES", "大对象媒体表",
                List.of(
                        simpleCol("ID", CanonicalTypeKind.INT64),
                        simpleCol("AMOUNT", CanonicalTypeKind.DECIMAL),
                        simpleCol("EVENT_DATE", CanonicalTypeKind.DATE),
                        simpleCol("CREATED_AT", CanonicalTypeKind.DATETIME),
                        simpleCol("CREATED_TZ", CanonicalTypeKind.TIMESTAMP_TZ),
                        simpleCol("PAYLOAD_BLOB", CanonicalTypeKind.BLOB),
                        simpleCol("CONTENT_CLOB", CanonicalTypeKind.CLOB)
                ),
                "PK_MEDIA", List.of("ID"), List.of(), List.of(), List.of(), null, 3L, null
        );
        CanonicalSchema schema = new CanonicalSchema(
                1, "oracle", "ORACLE", "19c", "AL32UTF8", "APP", null,
                List.of(table), List.of(), List.of()
        );

        Path pkgPath = tempDir.resolve("media-export.mddpkg");
        MddPackageWriter.WriteResult writeResult;
        // 设较小分片字节上限（4 MB）强制触发多分片切分
        try (MddPackageWriter writer = new MddPackageWriter(pkgPath, objectMapper, schema, "0.10.1", 4L * 1024 * 1024, 1000)) {
            try (MddPackageWriter.TableWriter tw = writer.beginTable(table, 6, "UTC")) {
                // 第 1 行：3MB BLOB + 3MB CLOB
                tw.writeRow(List.of(
                        CanonicalCell.ofInt64(1L),
                        CanonicalCell.ofDecimal(new BigDecimal("9876543210.123456")),
                        CanonicalCell.ofDate(LocalDate.of(2026, 11, 19)),
                        CanonicalCell.ofDateTime(LocalDateTime.of(2026, 11, 19, 16, 45, 30, 500_000_000)),
                        CanonicalCell.ofTimestampTz(OffsetDateTime.of(2026, 11, 19, 16, 45, 30, 0, ZoneOffset.ofHours(8))),
                        CanonicalCell.ofBlobStream(new ByteArrayInputStream(largeBlob), largeBlob.length),
                        CanonicalCell.ofClobStream(new StringReader(largeClob), largeClob.length())
                ));
                // 第 2 行：NULL + 小字节
                tw.writeRow(List.of(
                        CanonicalCell.ofInt64(2L),
                        CanonicalCell.ofNull(),
                        CanonicalCell.ofNull(),
                        CanonicalCell.ofDateTime(LocalDateTime.of(2025, 1, 2, 3, 4, 5)),
                        CanonicalCell.ofNull(),
                        CanonicalCell.ofBytes("small-bin".getBytes(StandardCharsets.UTF_8)),
                        CanonicalCell.ofString("短文本")
                ));
                // 第 3 行：再写一个 3MB BLOB 触发第一个分片写满关闭
                tw.writeRow(List.of(
                        CanonicalCell.ofInt64(3L),
                        CanonicalCell.ofDecimal(new BigDecimal("-42.5")),
                        CanonicalCell.ofDate(LocalDate.of(2000, 2, 29)),
                        CanonicalCell.ofDateTime(LocalDateTime.of(2000, 2, 29, 23, 59, 59)),
                        CanonicalCell.ofNull(),
                        CanonicalCell.ofBlobStream(new ByteArrayInputStream(largeBlob), largeBlob.length),
                        CanonicalCell.ofNull()
                ));
                // 第 4 行：落入第二个分片，验证多分片非空切分与完整还原
                tw.writeRow(List.of(
                        CanonicalCell.ofInt64(4L),
                        CanonicalCell.ofDecimal(new BigDecimal("0.01")),
                        CanonicalCell.ofDate(LocalDate.of(2026, 12, 31)),
                        CanonicalCell.ofDateTime(LocalDateTime.of(2026, 12, 31, 12, 0, 0)),
                        CanonicalCell.ofNull(),
                        CanonicalCell.ofBytes("part2-bin".getBytes(StandardCharsets.UTF_8)),
                        CanonicalCell.ofString("第二分片记录")
                ));
            }
            writeResult = writer.finish();
        }

        assertThat(Files.exists(pkgPath)).isTrue();
        assertThat(writeResult.manifest().totalRows()).isEqualTo(4L);
        assertThat(writeResult.manifest().tables().get(0).partCount()).isGreaterThanOrEqualTo(2);

        // 随机读取并逐字节验证还原结果与 L3 哈希
        try (MddPackageReader reader = new MddPackageReader(pkgPath, objectMapper)) {
            reader.verifyIntegrity();
            CanonicalSchema readSchema = reader.readSchema();
            assertThat(readSchema.tables()).hasSize(1);
            var checksums = reader.readChecksums();
            TableChecksum expectedCs = checksums.tables().get("t0001_media");
            assertThat(expectedCs.rowCount()).isEqualTo(4L);

            RowDigestAccumulator verifyAcc = new RowDigestAccumulator(
                    table.columns().stream().map(CanonicalColumn::name).toList(), 6, "UTC");
            List<Long> readIds = new ArrayList<>();
            byte[] recoveredBlob = null;
            String recoveredClob = null;

            for (ArtifactPartEntry part : reader.readManifest().tables().get(0).parts()) {
                try (MddPackageReader.PartCursor cursor = reader.openPart(part)) {
                    List<CanonicalCell> row;
                    while ((row = cursor.nextRow()) != null) {
                        try {
                            long id = row.get(0).asInt64();
                            readIds.add(id);
                            verifyAcc.recordRow(row);
                            if (id == 1L) {
                                try (InputStream bis = row.get(5).asBlobStream()) {
                                    recoveredBlob = bis.readAllBytes();
                                }
                                try (Reader cr = row.get(6).asClobStream()) {
                                    StringBuilder sb = new StringBuilder();
                                    char[] buf = new char[8192];
                                    int n;
                                    while ((n = cr.read(buf)) >= 0) sb.append(buf, 0, n);
                                    recoveredClob = sb.toString();
                                }
                            }
                        } finally {
                            for (CanonicalCell c : row) c.close();
                        }
                    }
                }
            }

            assertThat(readIds).containsExactly(1L, 2L, 3L, 4L);
            assertThat(recoveredBlob).isEqualTo(largeBlob);
            assertThat(recoveredClob).isEqualTo(largeClob);
            assertThat(verifyAcc.multisetHashHex()).isEqualTo(expectedCs.multisetHashHex());
        }

        // 篡改文件字节应触发校验失败
        Path tampered = tempDir.resolve("tampered.mddpkg");
        byte[] pkgBytes = Files.readAllBytes(pkgPath);
        pkgBytes[pkgBytes.length / 2] ^= 0x5A;
        Files.write(tampered, pkgBytes);
        assertThatThrownBy(() -> {
            try (MddPackageReader r = new MddPackageReader(tampered, objectMapper)) {
                r.verifyIntegrity();
            }
        }).isInstanceOf(Exception.class);
    }

    @Test
    void parallelTablesExportHandlesSpillEmptyTableAndSinglePassFileSha256(@TempDir Path tempDir) throws Exception {
        byte[] largeBlob = new byte[2_600_000];
        new Random(99L).nextBytes(largeBlob);

        CanonicalTable emptyTable = new CanonicalTable(
                "t0001_empty", "EMPTY_LOG", null,
                List.of(simpleCol("ID", CanonicalTypeKind.INT64), simpleCol("NOTE", CanonicalTypeKind.VARCHAR)),
                "PK_EMPTY", List.of("ID"), List.of(), List.of(), List.of(), null, 0L, null
        );
        CanonicalTable smallTable = new CanonicalTable(
                "t0002_small", "SMALL_ITEMS", null,
                List.of(simpleCol("ID", CanonicalTypeKind.INT64), simpleCol("LABEL", CanonicalTypeKind.VARCHAR)),
                "PK_SMALL", List.of("ID"), List.of(), List.of(), List.of(), null, 50L, null
        );
        CanonicalTable largeTable = new CanonicalTable(
                "t0003_large", "LARGE_BLOBS", null,
                List.of(simpleCol("ID", CanonicalTypeKind.INT64), simpleCol("DATA", CanonicalTypeKind.BLOB)),
                "PK_LARGE", List.of("ID"), List.of(), List.of(), List.of(), null, 2L, null
        );
        CanonicalSchema schema = new CanonicalSchema(
                1, "oracle", "ORACLE", "19c", "AL32UTF8", "APP", null,
                List.of(emptyTable, smallTable, largeTable), List.of(), List.of()
        );

        Path pkgPath = tempDir.resolve("parallel-export.mddpkg");
        MddPackageWriter.WriteResult writeResult;
        try (MddPackageWriter writer = new MddPackageWriter(pkgPath, objectMapper, schema, "0.10.1", 4L * 1024 * 1024, 1000)) {
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                Future<?> fEmpty = pool.submit(() -> {
                    try (MddPackageWriter.TableWriter tw = writer.beginParallelTable(emptyTable, 6, "UTC")) {
                        // 0 行空表：不写任何行
                    }
                    return null;
                });
                Future<?> fSmall = pool.submit(() -> {
                    try (MddPackageWriter.TableWriter tw = writer.beginParallelTable(smallTable, 6, "UTC")) {
                        for (long i = 1; i <= 50; i++) {
                            tw.writeRow(List.of(CanonicalCell.ofInt64(i), CanonicalCell.ofString("item-" + i)));
                        }
                    }
                    return null;
                });
                Future<?> fLarge = pool.submit(() -> {
                    try (MddPackageWriter.TableWriter tw = writer.beginParallelTable(largeTable, 6, "UTC")) {
                        // 3 行 × 2.6 MB：前 2 行超过 4 MB 关第 1 分片（触发溢写临时文件），第 3 行开启第 2 分片（也触发溢写临时文件）
                        for (long i = 1; i <= 3; i++) {
                            tw.writeRow(List.of(
                                    CanonicalCell.ofInt64(i),
                                    CanonicalCell.ofBlobStream(new ByteArrayInputStream(largeBlob), largeBlob.length)
                            ));
                        }
                    }
                    return null;
                });
                fEmpty.get();
                fSmall.get();
                fLarge.get();
            } finally {
                pool.shutdownNow();
            }
            writeResult = writer.finish();
        }

        // 1. 验证所有溢写临时文件已彻底清理（零残留）
        try (var stream = Files.list(tempDir)) {
            List<String> files = stream.map(p -> p.getFileName().toString()).toList();
            assertThat(files).containsExactly("parallel-export.mddpkg");
        }

        // 2. 验证单遍流式计算的 fileSha256 与落盘文件逐字节 SHA-256 完全一致
        MessageDigest md = RowDigestAccumulator.sha256();
        String expectedDiskSha = HexFormat.of().formatHex(md.digest(Files.readAllBytes(pkgPath)));
        assertThat(writeResult.fileSha256()).isEqualTo(expectedDiskSha);

        // 3. 验证空表零分片、大表多分片、且整包条目完整性通过
        Map<String, ArtifactTableEntry> byId = writeResult.manifest().tables().stream()
                .collect(Collectors.toMap(ArtifactTableEntry::tableId, Function.identity()));
        assertThat(byId.get("t0001_empty").rowCount()).isZero();
        assertThat(byId.get("t0001_empty").partCount()).isZero();
        assertThat(byId.get("t0001_empty").parts()).isEmpty();
        assertThat(byId.get("t0002_small").rowCount()).isEqualTo(50L);
        assertThat(byId.get("t0002_small").partCount()).isEqualTo(1);
        assertThat(byId.get("t0003_large").rowCount()).isEqualTo(3L);
        assertThat(byId.get("t0003_large").partCount()).isEqualTo(2);

        try (MddPackageReader reader = new MddPackageReader(pkgPath, objectMapper)) {
            reader.verifyIntegrity();
            assertThat(reader.readChecksums().tables().get("t0001_empty").rowCount()).isZero();
            assertThat(reader.readChecksums().tables().get("t0002_small").rowCount()).isEqualTo(50L);
            assertThat(reader.readChecksums().tables().get("t0003_large").rowCount()).isEqualTo(3L);
        }
    }

    private static CanonicalColumn simpleCol(String name, CanonicalTypeKind kind) {
        return new CanonicalColumn(name, kind, kind.name(), null, LengthUnit.NONE, null, null,
                false, false, false, 6, true, DefaultExprKind.NONE, null, null, null, null);
    }
}
