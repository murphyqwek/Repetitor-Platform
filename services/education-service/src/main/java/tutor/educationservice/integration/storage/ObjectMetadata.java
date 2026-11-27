package tutor.educationservice.integration.storage;

import org.springframework.http.MediaType;

public record ObjectMetadata(long size, String contentType) {
    public ObjectMetadata {
        if (size < -1) {
            throw new IllegalArgumentException("Object size must be >= 0 or -1 for unknown length");
        }
        if (contentType == null || contentType.isBlank() || contentType.indexOf('\r') >= 0
                || contentType.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid object content type");
        }
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            if (parsed.isWildcardType() || parsed.isWildcardSubtype()) {
                throw new IllegalArgumentException();
            }
            contentType = parsed.toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid object content type");
        }
    }
}
