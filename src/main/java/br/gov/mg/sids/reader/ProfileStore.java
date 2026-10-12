package br.gov.mg.sids.reader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Perfis por unidade. Webhook, token do bot e senha CAD opcional são cifrados com uma senha de proteção independente. */
final class ProfileStore {
    private final Path file;
    ProfileStore(Path file) {
        this.file = file;
    }

    /** Compatibilidade: salvar sem informar senha CAD nunca a persiste. */
    void save(Properties settings, String webhook, char[] protection) throws Exception {
        save(settings, webhook, "", protection);
    }

    /** Cifra cada segredo com sal e IV próprios; nunca grava a senha de proteção. */
    void save(Properties settings, String webhook, String cadPassword, char[] protection) throws Exception {
        Properties data = new Properties();
        data.putAll(settings);
        data.remove("password");
        data.remove("webhook");
        data.remove("webhook.encrypted");
        data.remove("password.encrypted");
        data.remove("botToken");
        data.remove("botToken.encrypted");
        encrypt(data, "botToken", settings.getProperty("botToken", ""), protection);
        encrypt(data, "webhook", webhook, protection);
        encrypt(data, "password", cadPassword, protection);
        AppFiles.save(file, data);
    }

    private void encrypt(Properties data, String field, String value, char[] protection) throws Exception {
        if (value.isEmpty())
            return;
        if (protection.length < 8)
            throw new IllegalArgumentException("Use pelo menos 8 caracteres na senha de proteção");
        byte[] salt = new byte[16], iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        Base64.Encoder encoder = Base64.getEncoder();
        data.setProperty(field + ".encrypted",
                         encoder.encodeToString(salt) + ":" + encoder.encodeToString(iv) + ":"
                             + encoder.encodeToString(cipher(Cipher.ENCRYPT_MODE, protection, salt, iv)
                                                          .doFinal(value.getBytes(StandardCharsets.UTF_8))));
    }

    /** Carrega preferências; usa a senha de proteção para recuperar os segredos salvos. */
    Properties load(char[] protection) throws Exception {
        if (!java.nio.file.Files.isRegularFile(file))
            throw new IllegalArgumentException("Perfil não encontrado. Confira o nome ou preencha as "
                                               + "configurações e clique em Salvar perfil.");
        Properties data = AppFiles.load(file);
        for (String field : new String[] {"webhook", "password", "botToken"}) {
            String encrypted = data.getProperty(field + ".encrypted", "");
            if (!encrypted.isBlank()) {
                String[] parts = encrypted.split(":");
                if (parts.length != 3)
                    throw new IllegalArgumentException("Perfil inválido");
                Base64.Decoder decoder = Base64.getDecoder();
                try {
                    Cipher cipher = cipher(Cipher.DECRYPT_MODE, protection, decoder.decode(parts[0]),
                                           decoder.decode(parts[1]));
                    data.setProperty(
                        field, new String(cipher.doFinal(decoder.decode(parts[2])), StandardCharsets.UTF_8));
                } catch (AEADBadTagException badPassword) {
                    throw new IllegalArgumentException("Senha de proteção incorreta ou perfil corrompido");
                }
            }
        }
        return data;
    }

    /** Deriva chave independente para cada perfil; sal e IV são públicos e aleatórios. */
    private static Cipher cipher(int mode, char[] password, byte[] salt, byte[] iv) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, 210000, 256);
        byte[] key;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return cipher;
        } finally {
            Arrays.fill(key, (byte)0);
        }
    }
}
