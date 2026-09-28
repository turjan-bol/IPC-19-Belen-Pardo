package com.ipc19.videodownloader;

import android.net.Uri;

import java.util.Locale;

/** Decide si una URL pedida por la página corresponde a un video. */
final class MediaDetector {

    static final String TYPE_VIDEO = "Video";
    static final String TYPE_HLS = "HLS (m3u8)";
    static final String TYPE_DASH = "DASH (mpd)";

    private static final String[] VIDEO_EXTENSIONS = {
            ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".3gp", ".flv", ".avi", ".wmv", ".ogv"
    };

    private MediaDetector() {}

    /** Devuelve el tipo de medio, o null si la URL no parece un video. */
    static String detect(String url) {
        if (url == null) return null;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null;

        Uri uri = Uri.parse(url);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);

        if (path.endsWith(".m3u8")) return TYPE_HLS;
        if (path.endsWith(".mpd")) return TYPE_DASH;
        for (String ext : VIDEO_EXTENSIONS) {
            if (path.endsWith(ext)) return TYPE_VIDEO;
        }

        String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.ROOT);
        if (query.contains("mime=video")) return TYPE_VIDEO;
        if (query.contains(".m3u8")) return TYPE_HLS;
        if (query.contains(".mp4")) return TYPE_VIDEO;
        return null;
    }

    /** Extensión de archivo sugerida para un video directo. */
    static String extensionFor(String url) {
        String path = Uri.parse(url).getPath();
        if (path != null) {
            String lower = path.toLowerCase(Locale.ROOT);
            for (String ext : VIDEO_EXTENSIONS) {
                if (lower.endsWith(ext)) return ext;
            }
        }
        return ".mp4";
    }

    /** Convierte un texto cualquiera en un nombre de archivo seguro. */
    static String safeFileName(String name) {
        if (name == null) name = "";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80).trim();
        if (cleaned.isEmpty()) cleaned = "video_" + System.currentTimeMillis();
        return cleaned;
    }
}
