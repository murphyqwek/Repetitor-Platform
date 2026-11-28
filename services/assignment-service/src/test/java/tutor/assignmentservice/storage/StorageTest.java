package tutor.assignmentservice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;

class StorageTest {
    @Test
    void rejectsInvalidKeysAndClosesTheUploadBodyBeforeContactingStorage() {
        MinioClient client = mock(MinioClient.class);
        var storage = new MinioObjectStorage(client, mock(MultipartStorageClient.class), "assignment-private");
        var body = new UploadBody();

        assertThatThrownBy(() -> storage.put("files/../education", body, 0, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(body.closed).isTrue();
        verifyNoInteractions(client);
    }

    @Test
    void translatesStorageOutagesWithoutLeakingConnectionInformation() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.statObject(any())).thenThrow(new IOException("internal-storage-address"));
        var storage = new MinioObjectStorage(client, mock(MultipartStorageClient.class), "assignment-private");

        assertThatThrownBy(() -> storage.head(ObjectStorage.newKey()))
                .isInstanceOfSatisfying(StorageException.class, exception -> {
                    assertThat(exception.reason()).isEqualTo(StorageException.Reason.UNAVAILABLE);
                    assertThat(exception.getMessage()).isEqualTo("Object storage unavailable");
                    assertThat(exception.getCause()).isNull();
                });
    }

    @Test
    void rejectsMissingCredentialsAndCredentialsInTheEndpoint() {
        assertThatThrownBy(() -> new StorageProperties(URI.create("http://localhost:9000"),
                "assignment-private", "", "secret")).hasMessageContaining("access key");
        assertThatThrownBy(() -> new StorageProperties(URI.create("http://user:secret@localhost:9000"),
                "assignment-private", "access", "secret"))
                .hasMessage("assignment.storage.endpoint must be an HTTP(S) origin");
    }


    @Test
    void retriesAnIdleConnectionEofOnceForAReadOnlyScan() {
        MinioClient client = mock(MinioClient.class);
        when(client.listObjects(any())).thenReturn(java.util.List.of(
                new io.minio.Result<io.minio.messages.Item>(new java.io.EOFException("closed idle connection"))))
                .thenReturn(java.util.List.of());
        var storage = new MinioObjectStorage(client, mock(MultipartStorageClient.class), "assignment-private");
        assertThat(storage.list(null, 100).keys()).isEmpty();
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).listObjects(any());
    }

    @Test
    void scanRetriesAreBoundedAndDoNotRepeatNonTransportErrors() {
        MinioClient client = mock(MinioClient.class);
        when(client.listObjects(any())).thenReturn(java.util.List.of(
                new io.minio.Result<io.minio.messages.Item>(new java.io.EOFException("offline"))));
        var storage = new MinioObjectStorage(client, mock(MultipartStorageClient.class), "assignment-private");
        assertThatThrownBy(() -> storage.list(null, 100)).isInstanceOf(StorageException.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).listObjects(any());
        org.mockito.Mockito.reset(client);
        when(client.listObjects(any())).thenThrow(new IllegalArgumentException("invalid response"));
        assertThatThrownBy(() -> storage.list(null, 100)).isInstanceOf(StorageException.class);
        org.mockito.Mockito.verify(client).listObjects(any());
    }

    private static final class UploadBody extends ByteArrayInputStream {
        private boolean closed;

        UploadBody() {
            super(new byte[0]);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
