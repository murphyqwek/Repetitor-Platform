package tutor.assignmentservice.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tutor.assignmentservice.common.error.ApiException;
import static org.assertj.core.api.Assertions.*;

class ImageUploadTest {
    @Test
    void verifiesSignaturesAndKeepsOnlyTheBaseName() throws Exception {
        byte[] jpeg = {(byte)255,(byte)216,(byte)255,0,(byte)255,(byte)217};
        try (var upload = ImageUpload.prepare(new MockMultipartFile("file","C:\\folder\\photo.jpg","image/jpeg",jpeg))) {
            assertThat(upload.name).isEqualTo("photo.jpg");
            assertThat(Files.readAllBytes(upload.path)).isEqualTo(jpeg);
            assertThat(upload.hash).isEqualTo(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(jpeg)));
        }
        byte[] webp = {'R','I','F','F',8,0,0,0,'W','E','B','P','V','P','8','L'};
        try (var upload = ImageUpload.prepare(new MockMultipartFile("file","image.webp","image/webp",webp))) {
            assertThat(upload.type).isEqualTo("image/webp");
            assertThat(upload.size).isEqualTo(webp.length);
        }
        assertThatThrownBy(()->ImageUpload.prepare(new MockMultipartFile("file","photo.jpg","image/jpeg",webp)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(415));
    }

    @Test
    void rejectsDishonestLengthAndControlCharactersInFileName() {
        byte[] jpeg = {(byte)255,(byte)216,(byte)255,0};
        var dishonest = new MockMultipartFile("file","photo.jpg","image/jpeg",jpeg) {
            @Override public long getSize() { return 1; }
        };
        assertThatThrownBy(()->ImageUpload.prepare(dishonest)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->ImageUpload.prepare(new MockMultipartFile("file","bad\r\nname.jpg","image/jpeg",jpeg)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(400));
        assertThatThrownBy(()->ImageUpload.prepare(new MockMultipartFile("file","x",null,jpeg)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(415));
    }
}
