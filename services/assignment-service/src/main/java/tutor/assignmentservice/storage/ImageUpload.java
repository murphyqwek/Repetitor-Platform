package tutor.assignmentservice.storage;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import tutor.assignmentservice.common.error.ApiException;

/** Validates into a bounded temporary file so the hashed bytes are exactly the bytes sent to S3. */
@Slf4j
final class ImageUpload implements AutoCloseable {
    static final long MAX_SIZE = 10_000_000;
    final Path path;
    final String name, type, hash;
    final long size;

    private ImageUpload(Path path, String name, String type, String hash, long size) {
        this.path = path;
        this.name = name;
        this.type = type;
        this.hash = hash;
        this.size = size;
    }

    static ImageUpload prepare(MultipartFile file) {
        if (file.getSize() > MAX_SIZE) throw tooLarge();
        String declared = file.getContentType();
        if (!Set.of("image/png", "image/jpeg", "image/webp").contains(declared == null ? "" : declared)) throw unsupported();
        Path path = null;
        boolean prepared = false;
        try {
            path = Files.createTempFile("assignment-image-", ".upload");
            var digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            byte[] first = new byte[16];
            try (var input = file.getInputStream(); var output = Files.newOutputStream(path)) {
                byte[] bytes = new byte[8192];
                int length;
                while ((length = input.read(bytes)) != -1) {
                    if (count + length > MAX_SIZE) throw tooLarge();
                    if (count < first.length) System.arraycopy(bytes, 0, first, (int)count, Math.min(length, first.length-(int)count));
                    output.write(bytes, 0, length);
                    digest.update(bytes, 0, length);
                    count += length;
                }
            }
            if (count != file.getSize() || !matches(first, count, declared)) throw unsupported();
            String name = file.getOriginalFilename();
            name = name == null ? "image" : name.replace('\\','/');
            name = name.substring(name.lastIndexOf('/') + 1);
            if (name.isBlank() || name.length()>255 || name.chars().anyMatch(Character::isISOControl))
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid image name");
            prepared = true;
            return new ImageUpload(path, name, declared, HexFormat.of().formatHex(digest.digest()), count);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Cannot read image");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        } finally {
            if (!prepared && path != null) remove(path);
        }
    }

    private static boolean matches(byte[] b, long size, String type) {
        return switch (type) {
            case "image/png" -> size >= 8 && Arrays.equals(Arrays.copyOf(b,8),
                    new byte[]{(byte)137,80,78,71,13,10,26,10});
            case "image/jpeg" -> size >= 3 && b[0]==(byte)255 && b[1]==(byte)216 && b[2]==(byte)255;
            case "image/webp" -> size >= 16 && b[0]=='R' && b[1]=='I' && b[2]=='F' && b[3]=='F'
                    && b[8]=='W' && b[9]=='E' && b[10]=='B' && b[11]=='P'
                    && b[12]=='V' && b[13]=='P' && b[14]=='8' && (b[15]==' ' || b[15]=='L' || b[15]=='X');
            default -> false;
        };
    }

    private static ApiException tooLarge() { return new ApiException(HttpStatus.CONTENT_TOO_LARGE,"FILE_TOO_LARGE","Image exceeds 10000000 bytes"); }
    private static ApiException unsupported() { return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"UNSUPPORTED_FILE_TYPE","Expected matching JPEG, PNG or WEBP signature and MIME type"); }

    @Override
    public void close() { remove(path); }

    private static void remove(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Cannot remove temporary upload file");
        }
    }
}
