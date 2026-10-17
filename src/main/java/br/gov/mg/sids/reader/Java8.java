package br.gov.mg.sids.reader;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Operações usadas pela aplicação com implementação disponível no Java 8. */
final class Java8 {
    private Java8() {}
    @SafeVarargs static <T> List<T> list(T... values) {
        return Collections.unmodifiableList(new ArrayList<T>(Arrays.asList(values)));
    }
    @SafeVarargs static <T> Set<T> set(T... values) {
        return Collections.unmodifiableSet(new HashSet<T>(Arrays.asList(values)));
    }
    @SuppressWarnings("unchecked") static <K, V> Map<K, V> map(Object... values) {
        if (values.length % 2 != 0) throw new IllegalArgumentException("Pares de chave e valor inválidos");
        Map<K, V> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((K)values[i], (V)values[i + 1]);
        return Collections.unmodifiableMap(result);
    }
    static <T> Set<T> setCopy(Collection<T> values) {
        return Collections.unmodifiableSet(new HashSet<T>(values));
    }
    static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        return output.toByteArray();
    }
    static String readString(Path path) throws IOException { return readString(path, StandardCharsets.UTF_8); }
    static String readString(Path path, Charset charset) throws IOException {
        return new String(Files.readAllBytes(path), charset);
    }
    static Path writeString(Path path, CharSequence text, OpenOption... options) throws IOException {
        return writeString(path, text, StandardCharsets.UTF_8, options);
    }
    static Path writeString(Path path, CharSequence text, Charset charset, OpenOption... options) throws IOException {
        return Files.write(path, text.toString().getBytes(charset), options);
    }
    static String repeat(String text, int count) {
        if (count < 0) throw new IllegalArgumentException("Contagem negativa");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }
    static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0) {
            int code = text.codePointBefore(end);
            if (!Character.isWhitespace(code)) break;
            end -= Character.charCount(code);
        }
        return text.substring(0, end);
    }
    static String encode(String text) {
        try { return java.net.URLEncoder.encode(text, "UTF-8"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
