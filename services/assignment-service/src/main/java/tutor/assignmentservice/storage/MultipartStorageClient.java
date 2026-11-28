package tutor.assignmentservice.storage;

import com.google.common.collect.HashMultimap;
import io.minio.MinioAsyncClient;
import io.minio.Xml;
import io.minio.http.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;

/** The local server omits optional Owner/Initiator fields required by the SDK's Upload DTO. */
final class MultipartStorageClient extends MinioAsyncClient {
    MultipartStorageClient(MinioAsyncClient client) { super(client); }

    CompletableFuture<Page> listExact(String bucket, String key, String keyMarker, String uploadMarker, int limit) {
        var query = HashMultimap.<String,String>create();
        query.put("uploads", "");
        query.put("prefix", key);
        query.put("max-uploads", Integer.toString(limit));
        if (keyMarker != null) query.put("key-marker", keyMarker);
        if (uploadMarker != null) query.put("upload-id-marker", uploadMarker);
        try {
            return getRegionAsync(bucket, null).thenCompose(region -> {
                try {
                    return executeAsync(Method.GET, bucket, null, region, null, query, null, 0);
                } catch (Exception ex) {
                    throw new CompletionException(ex);
                }
            }).thenApply(response -> {
                try (response) {
                    return Xml.unmarshal(Page.class, response.body().charStream());
                } catch (Exception ex) {
                    throw new CompletionException(ex);
                }
            });
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    @Root(name="ListMultipartUploadsResult", strict=false)
    public static class Page {
        @ElementList(entry="Upload", inline=true, required=false)
        public List<Upload> uploads;
        @Element(name="IsTruncated", required=false)
        public boolean truncated;
        @Element(name="NextKeyMarker", required=false)
        public String nextKey;
        @Element(name="NextUploadIdMarker", required=false)
        public String nextUpload;
    }

    @Root(name="Upload", strict=false)
    public static class Upload {
        @Element(name="Key")
        public String key;
        @Element(name="UploadId")
        public String uploadId;
    }
}
