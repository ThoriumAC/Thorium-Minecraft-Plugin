package ac.thorium.mc.plugin.capture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkinTextureTest {
    private static String b64(String json) { return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)); }

    @Test void readsTheMojangHash() {
        String hash = "a3f427a818c5ce5d3e2d0db1c3d2a1b0c9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4";
        String v = b64("{\"timestamp\":1,\"profileName\":\"Steve\",\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\",\"metadata\":{\"model\":\"slim\"}},\"CAPE\":{\"url\":\"http://textures.minecraft.net/texture/ffff\"}}}");
        assertEquals(hash, SkinTexture.hashFromProperty(v));
        String metadataFirst = b64("{\"textures\":{\"SKIN\":{\"metadata\":{\"model\":\"slim\"},\"url\":\"https://textures.minecraft.net/texture/" + hash + "\"}}}");
        assertEquals(hash, SkinTexture.hashFromProperty(metadataFirst));
    }

    @Test void ignoresCapesOnlyForeignHostsAndJunk() {
        assertEquals("", SkinTexture.hashFromProperty(b64("{\"textures\":{\"CAPE\":{\"url\":\"http://textures.minecraft.net/texture/abcd\"}}}")));
        assertEquals("", SkinTexture.hashFromProperty(b64("{\"textures\":{\"SKIN\":{\"url\":\"https://evil.example/texture/abcd\"}}}")));
        assertEquals("", SkinTexture.hashFromProperty("not base64 !!"));
        assertEquals("", SkinTexture.hashFromProperty(null));
    }
}
