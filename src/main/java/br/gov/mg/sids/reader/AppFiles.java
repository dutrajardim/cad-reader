package br.gov.mg.sids.reader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Properties;

/** Arquivos locais, gravação atômica e escape JSON sem dependências externas. */
final class AppFiles {
    private AppFiles() {
    }
    static Properties load(Path file) throws IOException {
        Properties result = new Properties();
        if (Files.exists(file))
            try (Reader in = Files.newBufferedReader(file)) {
                result.load(in);
            }
        return result;
    }
    static void save(Path file, Properties data) throws IOException {
        StringWriter out = new StringWriter();
        data.store(out, "CAD Reader");
        write(file, out.toString());
    }
    static void write(Path file, String data) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), ".cad-", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
            Files.writeString(temp, data, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
    static String hash(String value) {
        try {
            byte[] digest =
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            char[] hex = "0123456789abcdef".toCharArray();
            for (byte b : digest) { out.append(hex[(b & 255) >>> 4]); out.append(hex[b & 15]); }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"')
                out.append('\\').append(c);
            else if (c < 32)
                out.append(String.format("\\u%04x", (int) c));
            else
                out.append(c);
        }
        return out.append('"').toString();
    }
}
