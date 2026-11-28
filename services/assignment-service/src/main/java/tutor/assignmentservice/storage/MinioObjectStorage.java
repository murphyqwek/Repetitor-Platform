package tutor.assignmentservice.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class MinioObjectStorage implements ObjectStorage {
    private static final int PART_SIZE = 10 * 1024 * 1024;
    private static final Pattern KEY = Pattern.compile("files/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private final MinioClient client;
    private final MultipartStorageClient maintenance;
    private final String bucket;

    MinioObjectStorage(MinioClient client, MultipartStorageClient maintenance, String bucket) {
        this.client = client;
        this.maintenance = maintenance;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, InputStream body, long size, String contentType) {
        try (var stream = new BufferedInputStream(Objects.requireNonNull(body))) {
            validate(key);
            if (size < -1 || contentType == null || contentType.isBlank() || contentType.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid object metadata");
            }
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .headers(java.util.Map.of("If-None-Match", "*"))
                    .stream(stream, size, size == -1 ? PART_SIZE : -1).contentType(contentType).build());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw translate(exception, "put");
        }
    }

    @Override
    public InputStream get(String key) {
        validate(key);
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw translate(exception, "get");
        }
    }

    @Override
    public Metadata head(String key) {
        validate(key);
        try {
            var object = client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return new Metadata(object.size(), object.contentType());
        } catch (Exception exception) {
            throw translate(exception, "head");
        }
    }

    @Override
    public void delete(String key) {
        validate(key);
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            StorageException failure = translate(exception, "delete");
            if (failure.reason() != StorageException.Reason.NOT_FOUND) {
                throw failure;
            }
        }
    }


    @Override
    public ObjectPage list(String after, int limit) {
        checkLimit(limit);
        try {
            return listPage(after, limit);
        } catch (Exception first) {
            if (!(rootCause(first) instanceof java.io.IOException)) throw translate(first, "list");
            // A page read is safe to repeat once after a closed idle connection or another transport failure.
            try {
                return listPage(after, limit);
            } catch (Exception second) {
                throw translate(second, "list");
            }
        }
    }

    private ObjectPage listPage(String after, int limit) throws Exception {
        var keys = new java.util.ArrayList<String>();
        int count = 0;
        String last = null;
        var args = io.minio.ListObjectsArgs.builder().bucket(bucket).prefix("files/").recursive(true)
                .startAfter(after).maxKeys(limit).build();
        for (var item : client.listObjects(args)) {
            String key = item.get().objectName();
            last = key;
            count++;
            if (KEY.matcher(key).matches()) keys.add(key);
            // Count S3 entries, including unexpected names, to bound every pass.
            if (count == limit) break;
        }
        return new ObjectPage(java.util.List.copyOf(keys), count == limit ? last : null);
    }

    @Override
    public MultipartPage listMultipart(String key, String keyMarker, String uploadMarker, int limit) {
        validate(key);
        checkLimit(limit);
        try {
            var result = maintenance.listExact(bucket, key, keyMarker, uploadMarker, limit)
                    .get(65, java.util.concurrent.TimeUnit.SECONDS);
            var found = result.uploads == null ? java.util.List.<MultipartStorageClient.Upload>of() : result.uploads;
            var uploads = found.stream().filter(u -> key.equals(u.key))
                    .map(u -> new Multipart(u.key, u.uploadId)).toList();
            return new MultipartPage(uploads, result.truncated ? result.nextKey : null,
                    result.truncated ? result.nextUpload : null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StorageException(StorageException.Reason.UNAVAILABLE);
        } catch (Exception ex) {
            throw translate(ex, "multipart-list");
        }
    }

    @Override
    public void abortMultipart(String key, String uploadId) {
        validate(key);
        try {
            maintenance.abortMultipartUploadAsync(bucket, null, key, uploadId, null, null)
                    .get(65, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new StorageException(StorageException.Reason.UNAVAILABLE);
        } catch (Exception ex) {
            Throwable cause = ex instanceof java.util.concurrent.ExecutionException ? ex.getCause() : ex;
            if (cause instanceof ErrorResponseException response && "NoSuchUpload".equals(response.errorResponse().code())) return;
            throw translate(ex, "multipart-abort");
        }
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Storage scan limit must be 1..1000");
    }

    private static void validate(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Object key must be generated by ObjectStorage");
        }
    }

    private static Throwable rootCause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
    }

    private static StorageException translate(Exception exception, String operation) {
        if (exception instanceof ErrorResponseException response) {
            String code = response.errorResponse().code();
            if ("PreconditionFailed".equals(code) || "ConditionalRequestConflict".equals(code)) return new StorageException(StorageException.Reason.ALREADY_EXISTS);
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return new StorageException(StorageException.Reason.NOT_FOUND);
            }
        }
        Throwable root = rootCause(exception);
        String code = root instanceof ErrorResponseException response ? response.errorResponse().code() : "-";
        log.warn(
                "Object storage request failed, operation={}, error={}, code={}, at={}",
                operation, root.getClass().getSimpleName(), code,
                root.getStackTrace().length == 0 ? "-" : root.getStackTrace()[0]);
        return new StorageException(StorageException.Reason.UNAVAILABLE);
    }
}
