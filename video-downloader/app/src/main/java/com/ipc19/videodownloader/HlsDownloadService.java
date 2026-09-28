package com.ipc19.videodownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descarga un stream HLS (.m3u8): elige la mejor calidad, baja todos los
 * segmentos y los une en un único archivo dentro de Descargas.
 */
public class HlsDownloadService extends Service {

    static final String EXTRA_URL = "url";
    static final String EXTRA_REFERER = "referer";
    static final String EXTRA_USER_AGENT = "user_agent";
    static final String EXTRA_COOKIES = "cookies";
    static final String EXTRA_NAME = "name";
    static final String FOLDER = "VideoDownloader";

    private static final String CHANNEL_ID = "downloads";
    private static final int FOREGROUND_ID = 1;
    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger nextNotificationId = new AtomicInteger(100);
    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = builder()
                .setContentTitle("Preparando descarga…")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FOREGROUND_ID, n);
        }

        if (intent == null || intent.getStringExtra(EXTRA_URL) == null) {
            finishIfIdle();
            return START_NOT_STICKY;
        }

        Job job = new Job(
                intent.getStringExtra(EXTRA_URL),
                intent.getStringExtra(EXTRA_REFERER),
                intent.getStringExtra(EXTRA_USER_AGENT),
                intent.getStringExtra(EXTRA_COOKIES),
                intent.getStringExtra(EXTRA_NAME));
        pending.incrementAndGet();
        executor.execute(() -> {
            try {
                run(job);
            } finally {
                pending.decrementAndGet();
                finishIfIdle();
            }
        });
        return START_NOT_STICKY;
    }

    private void finishIfIdle() {
        if (pending.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ Trabajo

    private static final class Job {
        final String url, referer, userAgent, cookies, name;

        Job(String url, String referer, String userAgent, String cookies, String name) {
            this.url = url;
            this.referer = referer;
            this.userAgent = userAgent;
            this.cookies = cookies;
            this.name = MediaDetector.safeFileName(name);
        }
    }

    private void run(Job job) {
        int notifId = nextNotificationId.incrementAndGet();
        try {
            progress(notifId, job.name, "Leyendo lista de reproducción…", 0, 0);

            String playlistUrl = job.url;
            String playlist = fetchText(playlistUrl, job);

            // Lista maestra: elegir la variante de mayor calidad.
            if (playlist.contains("#EXT-X-STREAM-INF")) {
                playlistUrl = pickBestVariant(playlist, playlistUrl);
                playlist = fetchText(playlistUrl, job);
            }

            if (!playlist.contains("#EXTINF")) {
                throw new IOException("La lista no contiene segmentos de video");
            }
            if (playlist.contains("#EXT-X-BYTERANGE")) {
                throw new IOException("Este formato de lista (BYTERANGE) no está soportado");
            }

            String initSegment = null;
            List<String> segments = new ArrayList<>();
            for (String raw : playlist.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("#EXT-X-KEY")) {
                    String upper = line.toUpperCase(Locale.ROOT);
                    if (!upper.contains("METHOD=NONE")) {
                        throw new IOException("El video está protegido (cifrado/DRM) y no se puede descargar");
                    }
                } else if (line.startsWith("#EXT-X-MAP")) {
                    Matcher m = URI_ATTR.matcher(line);
                    if (m.find()) initSegment = resolve(playlistUrl, m.group(1));
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    segments.add(resolve(playlistUrl, line));
                }
            }
            if (segments.isEmpty()) throw new IOException("No se encontraron segmentos");

            boolean fmp4 = initSegment != null || segments.get(0).toLowerCase(Locale.ROOT).contains(".m4s")
                    || segments.get(0).toLowerCase(Locale.ROOT).contains(".mp4");
            String ext = fmp4 ? ".mp4" : ".ts";
            String mime = fmp4 ? "video/mp4" : "video/mp2t";
            String fileName = job.name + ext;

            Output out = openOutput(fileName, mime);
            try {
                byte[] buffer = new byte[64 * 1024];
                if (initSegment != null) copyWithRetry(initSegment, job, out.stream, buffer);
                for (int i = 0; i < segments.size(); i++) {
                    progress(notifId, job.name, "Segmento " + (i + 1) + " de " + segments.size(),
                            i, segments.size());
                    copyWithRetry(segments.get(i), job, out.stream, buffer);
                }
                out.stream.close();
                out.commit();
            } catch (Exception e) {
                try { out.stream.close(); } catch (IOException ignored) {}
                out.discard();
                throw e;
            }

            done(notifId, "Descarga completa", "Descargas/" + FOLDER + "/" + fileName);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            done(notifId, "Error al descargar " + job.name, msg);
        }
    }

    private static String pickBestVariant(String master, String baseUrl) throws IOException {
        String[] lines = master.split("\n");
        long bestBandwidth = -1;
        String best = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue;
            long bw = 0;
            Matcher m = BANDWIDTH.matcher(line);
            if (m.find()) bw = Long.parseLong(m.group(1));
            // La URL de la variante es la siguiente línea no vacía que no sea comentario.
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (next.isEmpty() || next.startsWith("#")) continue;
                if (bw > bestBandwidth) {
                    bestBandwidth = bw;
                    best = next;
                }
                break;
            }
        }
        if (best == null) throw new IOException("No se encontró ninguna calidad en la lista");
        return resolve(baseUrl, best);
    }

    private static String resolve(String base, String relative) throws IOException {
        return new URL(new URL(base), relative).toString();
    }

    // ---------------------------------------------------------------- Red

    private static HttpURLConnection open(String url, Job job) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        if (job.userAgent != null) c.setRequestProperty("User-Agent", job.userAgent);
        if (job.referer != null) c.setRequestProperty("Referer", job.referer);
        if (job.cookies != null) c.setRequestProperty("Cookie", job.cookies);
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new IOException("El servidor respondió " + code);
        }
        return c;
    }

    private static String fetchText(String url, Job job) throws IOException {
        HttpURLConnection c = open(url, job);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /**
     * Descarga un segmento completo en memoria antes de escribirlo, para que un
     * reintento no deje datos duplicados en el archivo final.
     */
    private static void copyWithRetry(String url, Job job, OutputStream out, byte[] buffer) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpURLConnection c = open(url, job);
                java.io.ByteArrayOutputStream seg = new java.io.ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while ((n = in.read(buffer)) != -1) seg.write(buffer, 0, n);
                } finally {
                    c.disconnect();
                }
                seg.writeTo(out);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(1000L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Descarga cancelada");
                }
            }
        }
        throw last;
    }

    // ------------------------------------------------------------ Almacenamiento

    private abstract static class Output {
        OutputStream stream;
        abstract void commit();
        abstract void discard();
    }

    private Output openOutput(String fileName, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            final Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("No se pudo crear el archivo en Descargas");
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("No se pudo abrir el archivo de salida");
            Output out = new Output() {
                @Override
                void commit() {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                }

                @Override
                void discard() {
                    cr.delete(uri, null, null);
                }
            };
            out.stream = os;
            return out;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("No se pudo crear la carpeta de descargas");
        final File file = uniqueFile(dir, fileName);
        Output out = new Output() {
            @Override
            void commit() {
                MediaScannerConnection.scanFile(HlsDownloadService.this,
                        new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
            }

            @Override
            void discard() {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        };
        out.stream = new FileOutputStream(file);
        return out;
    }

    private static File uniqueFile(File dir, String fileName) {
        File f = new File(dir, fileName);
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; f.exists(); i++) f = new File(dir, stem + " (" + i + ")" + ext);
        return f;
    }

    // ------------------------------------------------------------ Notificaciones

    private Notification.Builder builder() {
        return new Notification.Builder(this, CHANNEL_ID);
    }

    private void progress(int id, String title, String text, int value, int max) {
        Notification n = builder()
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(max, value, max == 0)
                .build();
        nm.notify(id, n);
    }

    private void done(int id, String title, String text) {
        Notification n = builder()
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }
}
