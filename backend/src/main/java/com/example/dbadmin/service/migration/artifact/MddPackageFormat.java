package com.example.dbadmin.service.migration.artifact;

import java.nio.charset.StandardCharsets;

/**
 * MyDataDev 通用标准制品（{@code .mddpkg}）的二进制分片帧协议常量与智能压缩检测。
 */
public final class MddPackageFormat {
    public static final String EXTENSION = ".mddpkg";
    public static final int FORMAT_VERSION = 1;
    public static final byte[] MAGIC = "MDDB".getBytes(StandardCharsets.US_ASCII);

    public static final String ENTRY_MANIFEST = "manifest.json";
    public static final String ENTRY_SCHEMA = "schema.json";
    public static final String ENTRY_CHECKSUMS = "verify/checksums.json";

    public static final byte ROW_PRESENT = 0x01;
    public static final byte ROW_EOF = 0x00;

    public static final byte TAG_NULL = 0;
    public static final byte TAG_BOOLEAN = 1;
    public static final byte TAG_INT64 = 2;
    public static final byte TAG_FLOAT64 = 3;
    public static final byte TAG_DECIMAL = 4;
    public static final byte TAG_STRING = 5;
    public static final byte TAG_DATE = 6;
    public static final byte TAG_TIME = 7;
    public static final byte TAG_DATETIME = 8;
    public static final byte TAG_TIMESTAMP_TZ = 9;
    public static final byte TAG_BYTES = 10;
    public static final byte TAG_STREAM_BLOB = 11;
    public static final byte TAG_STREAM_CLOB = 12;

    public static final byte CODEC_RAW = 0;
    public static final byte CODEC_GZIP = 1;

    public static final int IO_BUFFER_BYTES = 256 * 1024;
    public static final int INLINE_LOB_MAX_BYTES = 64 * 1024;

    private MddPackageFormat() {
    }

    /**
     * 判断二进制 BLOB 采样头是否值得 GZIP 压缩。
     * 已经高度压缩的常见多媒体/归档魔数（JPEG/PNG/PDF/ZIP/GZIP/MP4/ZSTD/7z/XZ）直接返回 false，避免烧 CPU。
     */
    public static boolean shouldCompressBinary(byte[] head, int len) {
        if (head == null || len < 512) return false;
        // JPEG: FF D8 FF
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return false;
        // PNG: 89 50 4E 47
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return false;
        // GIF: GIF8
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') return false;
        // ZIP / DOCX / XLSX / JAR: 50 4B 03 04
        if (head[0] == 'P' && head[1] == 'K' && head[2] == 0x03 && head[3] == 0x04) return false;
        // GZIP: 1F 8B
        if ((head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) return false;
        // PDF: %PDF
        if (head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F') return false;
        // ZSTD: 28 B5 2F FD
        if ((head[0] & 0xFF) == 0x28 && (head[1] & 0xFF) == 0xB5 && (head[2] & 0xFF) == 0x2F && (head[3] & 0xFF) == 0xFD) return false;
        // 7z: 37 7A BC AF
        if ((head[0] & 0xFF) == 0x37 && (head[1] & 0xFF) == 0x7A && (head[2] & 0xFF) == 0xBC && (head[3] & 0xFF) == 0xAF) return false;
        // MP4 / MOV: ....ftyp
        if (len >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return false;
        // WebP: RIFF....WEBP
        if (len >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return false;
        return true;
    }
}
