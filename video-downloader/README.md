# Descargador de Videos (Android)

App Android (APK) para descargar videos incrustados en páginas web que no
ofrecen un botón de "descargar".

## Cómo conseguir el APK

Cada vez que se sube un cambio a esta carpeta, GitHub Actions compila la app y
publica el archivo **`DescargadorDeVideos.apk`** en la sección **Releases** del
repositorio (versión `apk-latest`).

1. Abre la página de Releases desde el teléfono y descarga `DescargadorDeVideos.apk`.
2. Ábrelo; Android pedirá permitir "instalar apps de orígenes desconocidos" para
   el navegador o el gestor de archivos. Acéptalo e instala.

Requiere Android 8.0 o superior.

## Cómo se usa

1. Abre la app: es un navegador. Escribe la dirección de la página que tiene el
   video (o comparte un enlace hacia la app desde otra aplicación con
   *Compartir → Descargador de Videos*).
2. **Reproduce el video** en la página. Mientras se reproduce, la app detecta
   las direcciones reales del video, incluso si está dentro de un reproductor
   incrustado (iframe) de otro sitio.
3. Toca el botón naranja **Videos (N)**, elige el video y pulsa **Descargar**.
4. El archivo queda en **Descargas/VideoDownloader**.

## Qué formatos soporta

| Formato | Soporte |
|---|---|
| Archivos directos (MP4, WebM, MOV, MKV, 3GP…) | Sí, con el gestor de descargas de Android |
| HLS (`.m3u8`) sin cifrar | Sí: baja la mejor calidad y une los segmentos en un `.ts` o `.mp4` |
| DASH (`.mpd`) | Se detecta pero todavía no se descarga |
| Videos con DRM o HLS cifrado (Netflix, Disney+, etc.) | No, y no está previsto |

Si un video HLS guarda el audio en una pista separada, el archivo descargado
puede quedar sin sonido. Los `.ts` se reproducen con VLC u otros reproductores.

Usa la app solo con videos que tengas derecho a guardar.

## Compilar localmente

Con Android SDK y Gradle 8.9 instalados:

```bash
cd video-downloader
gradle assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```
