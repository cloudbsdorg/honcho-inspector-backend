package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.config.HonchoConfigDirResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoServiceTest {

    private static final byte[] TEST_KEY_BYTES = new byte[32];
    static {
        for (int i = 0; i < 32; i++) TEST_KEY_BYTES[i] = (byte) i;
    }
    private static final String TEST_KEY_B64 = Base64.getEncoder().encodeToString(TEST_KEY_BYTES);

    @Test
    void loadsKeyFromEnvWhenPresent() {
        var crypto = new CryptoService(TEST_KEY_B64, mock(HonchoConfigDirResolver.class));
        assertFalse(crypto.isEphemeral(), "key from env should not be ephemeral");
    }

    @Test
    void loadsKeyFromFileWhenPresent(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("honcho.crypto-key"), TEST_KEY_B64);
        var configDir = mock(HonchoConfigDirResolver.class);
        when(configDir.resolve()).thenReturn(tempDir);
        var crypto = new CryptoService("", configDir);
        assertFalse(crypto.isEphemeral(), "key from file should not be ephemeral");
    }

    @Test
    void fallsBackToEphemeralWhenNeitherEnvNorFilePresent(@TempDir Path tempDir) {
        var configDir = mock(HonchoConfigDirResolver.class);
        when(configDir.resolve()).thenReturn(tempDir);
        var crypto = new CryptoService("", configDir);
        assertTrue(crypto.isEphemeral(), "no env and no file should be ephemeral");
    }

    @Test
    void reloadKeyFromFileSwapsInMemoryKeyAndClearsEphemeralFlag(@TempDir Path tempDir) throws Exception {
        var configDir = mock(HonchoConfigDirResolver.class);
        when(configDir.resolve()).thenReturn(tempDir);
        var crypto = new CryptoService("", configDir);
        assertTrue(crypto.isEphemeral(), "starts ephemeral");

        Files.writeString(tempDir.resolve("honcho.crypto-key"), TEST_KEY_B64);
        crypto.reloadKeyFromFile(configDir);

        assertFalse(crypto.isEphemeral(), "reload should clear ephemeral flag");
    }

    @Test
    void reloadKeyFromFileThrowsWhenFileMissing(@TempDir Path tempDir) {
        var configDir = mock(HonchoConfigDirResolver.class);
        when(configDir.resolve()).thenReturn(tempDir);
        var crypto = new CryptoService("", configDir);
        assertThrows(IllegalStateException.class, () -> crypto.reloadKeyFromFile(configDir));
    }

    @Test
    void reloadKeyFromFileThrowsOnInvalidBase64(@TempDir Path tempDir) throws Exception {
        var configDir = mock(HonchoConfigDirResolver.class);
        when(configDir.resolve()).thenReturn(tempDir);
        var crypto = new CryptoService("", configDir);
        Files.writeString(tempDir.resolve("honcho.crypto-key"), "@@@not-base64@@@");
        assertThrows(IllegalStateException.class, () -> crypto.reloadKeyFromFile(configDir));
    }

    @Test
    void reloadKeyFromFileThrowsOnWrongKeyLength(@TempDir Path tempDir) throws Exception {
        var configDir = mock(HonchoConfigDirResolver.class);
        when(configDir.resolve()).thenReturn(tempDir);
        var crypto = new CryptoService("", configDir);
        var tooShort = Base64.getEncoder().encodeToString(new byte[16]);
        Files.writeString(tempDir.resolve("honcho.crypto-key"), tooShort);
        assertThrows(IllegalStateException.class, () -> crypto.reloadKeyFromFile(configDir));
    }

    @Test
    void persistKeyToFileWritesBase64EncodedKey(@TempDir Path tempDir) throws Exception {
        var configDir = mock(HonchoConfigDirResolver.class);
        when(configDir.resolve()).thenReturn(tempDir);
        var crypto = new CryptoService("", configDir);
        crypto.persistKeyToFile(configDir);
        var content = Files.readString(tempDir.resolve("honcho.crypto-key")).trim();
        assertNotNull(content);
        var decoded = Base64.getDecoder().decode(content);
        assertEquals(32, decoded.length, "persisted key must decode to 32 bytes");
    }
}
