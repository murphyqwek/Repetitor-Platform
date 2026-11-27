package tutor.educationservice.chat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.integration.storage.PendingUploads;

record ChatFileFormat(String name, String contentType) {
    static final long MAX_BYTES = 20_000_000;

    static ChatFileFormat check(MultipartFile file) {
        if (file.getSize() > MAX_BYTES) throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "Chat file exceeds 20 MB");
        String name = file.getOriginalFilename();
        if (name == null || name.length() > 255 || name.codePoints().anyMatch(Character::isISOControl)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.lastIndexOf('.') <= 0) throw unsupported();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "txt" -> "text/plain";
            default -> throw unsupported();
        };
        if (!MediaType.parseMediaType(PendingUploads.contentType(file.getContentType())).equalsTypeAndSubtype(MediaType.parseMediaType(mime))) {
            throw unsupported();
        }
        try (var input = new BufferedInputStream(file.getInputStream())) {
            boolean valid = switch (extension) {
                case "txt" -> text(input);
                case "docx" -> zip(input, "word/document.xml");
                case "xlsx" -> zip(input, "xl/workbook.xml");
                case "png" -> Arrays.equals(input.readNBytes(8), new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
                case "jpg", "jpeg" -> Arrays.equals(input.readNBytes(3), new byte[]{(byte) 255, (byte) 216, (byte) 255});
                case "pdf" -> signature(input.readNBytes(5), 0, "%PDF-");
                case "webp" -> {
                    byte[] bytes = input.readNBytes(16);
                    yield signature(bytes, 0, "RIFF") && signature(bytes, 8, "WEBP") &&
                            (signature(bytes, 12, "VP8 ") || signature(bytes, 12, "VP8L") || signature(bytes, 12, "VP8X"));
                }
                default -> false;
            };
            if (!valid) throw unsupported();
        } catch (IOException exception) {
            throw unsupported();
        }
        return new ChatFileFormat(name, mime);
    }

    private static boolean signature(byte[] data, int offset, String signature) {
        return data.length >= offset + signature.length()
                && new String(data, offset, signature.length(), StandardCharsets.US_ASCII).equals(signature);
    }

    private static boolean text(InputStream input) throws IOException {
        var reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder());
        char[] buffer = new char[8192];
        int count;
        while ((count = reader.read(buffer)) >= 0) {
            for (int i = 0; i < count; i++) {
                char c = buffer[i];
                if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r') return false;
            }
        }
        return true;
    }

    private static boolean zip(InputStream input, String document) throws IOException {
        try (var zip = new ZipInputStream(input)) {
            boolean manifest = false;
            boolean content = false;
            int entries = 0;
            long expanded = 0;
            byte[] buffer = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 10_000) return false;
                manifest |= entry.getName().equals("[Content_Types].xml");
                content |= entry.getName().equals(document);
                int count;
                while ((count = zip.read(buffer)) >= 0) {
                    expanded += count;
                    if (expanded > 128_000_000) return false;
                }
                if (manifest && content) return true;
            }
            return false;
        }
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported chat file");
    }
}
