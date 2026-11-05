package com.githistory.ui;

import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Avatares de Gravatar con caché en memoria. La descarga es bloqueante con
 * timeout corto: llamar siempre desde un hilo de fondo. Si el autor no tiene
 * Gravatar se devuelve el identicon generado a partir de su email (como hace
 * GitKraken), y si falla la red se devuelve null para que la UI use las
 * iniciales.
 */
public final class AvatarService {

    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private AvatarService() {}

    /** URL de Gravatar para un email (normalizado: trim + minúsculas, MD5). */
    public static String gravatarUrl(String email, int size) {
        return "https://www.gravatar.com/avatar/" + md5(email.trim().toLowerCase())
                + "?s=" + size + "&d=identicon";
    }

    /**
     * Descarga (o sirve de caché) el avatar de un email. Bloqueante.
     *
     * @return la imagen, o null si el email es inválido o la red falla.
     */
    public static Image load(String email, int size) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String key = email.trim().toLowerCase() + "|" + size;
        Image cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (FAILED.contains(key)) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gravatarUrl(email, size)))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                FAILED.add(key);
                return null;
            }
            Image image = new Image(new ByteArrayInputStream(response.body()));
            if (image.isError()) {
                FAILED.add(key);
                return null;
            }
            CACHE.put(key, image);
            return image;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            FAILED.add(key);
            return null;
        }
    }

    static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 no disponible en esta JVM", e);
        }
    }
}
