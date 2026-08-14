package com.example.dbadmin.storage;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.function.LongConsumer;

public interface BackupStorage {
    String type();

    void test(StorageConnection connection) throws Exception;

    void upload(StorageConnection connection, Path source, String objectKey, LongConsumer progress) throws Exception;

    void download(StorageConnection connection, String objectKey, OutputStream output, LongConsumer progress) throws Exception;

    long size(StorageConnection connection, String objectKey) throws Exception;

    void delete(StorageConnection connection, String objectKey) throws Exception;
}
