package ru.slavapmk.pixelgram;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class PixelGramUpdater {
    public static String latestApkUrl = null; // Обязательный сброс

    // Сохраняем ссылки для корректной отмены
    private static volatile InputStream downloadStream;
    private static volatile HttpURLConnection downloadConnection;

    public interface UpdateCallback {
        void onResult(TLRPC.TL_help_appUpdate update, boolean hasUpdate);

        void onError();
    }

    public static void check(UpdateCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            latestApkUrl = null;
            try {
                URL url = new URL("https://api.github.com/repos/slavapmk/PixelGram/releases/latest");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(10000); // Таймаут 10 сек
                conn.setReadTimeout(15000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    JSONObject json = new JSONObject(sb.toString());
                    String tagName = json.getString("tag_name");
                    String body = json.optString("body", "Новая версия PixelGram!");

                    JSONArray assets = json.getJSONArray("assets");
                    long size = 0;

                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if ("PixelGram.apk".equals(asset.getString("name"))) {
                            latestApkUrl = asset.getString("browser_download_url");
                            size = asset.optLong("size", 0);
                            break;
                        }
                    }

                    if (TextUtils.isEmpty(latestApkUrl)) {
                        AndroidUtilities.runOnUIThread(callback::onError);
                        return;
                    }

                    // Очищаем тег от "v" (например: v10.14.0.1 -> 10.14.0.1)
                    String remoteVersionName = tagName.replace("v", "").trim();

                    String currentVersionName = "0.0.0";
                    try {
                        android.content.pm.PackageInfo pInfo = ApplicationLoader.applicationContext
                                .getPackageManager()
                                .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                        currentVersionName = pInfo.versionName;
                    } catch (Exception e) {
                        FileLog.e(e);
                    }

                    // Умное сравнение версий по точкам (например, 10.14.1 > 10.14.0.1)
                    if (compareVersions(remoteVersionName, currentVersionName) > 0) {
                        TLRPC.TL_help_appUpdate update = new TLRPC.TL_help_appUpdate();
                        update.version = remoteVersionName; // Отдаем чистую версию в UI
                        update.text = body;
                        update.can_not_skip = false;
                        update.document = new TLRPC.TL_document();
                        update.document.size = size;

                        // Фейковый документ исключительно для отрисовки UI
                        TLRPC.TL_documentAttributeFilename fileNameAttr = new TLRPC.TL_documentAttributeFilename();
                        fileNameAttr.file_name = "PixelGram_Update.apk";
                        update.document.attributes.add(fileNameAttr);

                        AndroidUtilities.runOnUIThread(() -> callback.onResult(update, true));
                    } else {
                        AndroidUtilities.runOnUIThread(() -> callback.onResult(null, false));
                    }
                } else {
                    AndroidUtilities.runOnUIThread(callback::onError);
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(callback::onError);
            }
        });
    }

    public static volatile boolean isDownloading = false;
    public static volatile float downloadProgress = 0f;
    public static volatile boolean isDownloaded = false; // Флаг для UpdateLayout

    public static void startDownload(TLRPC.Document document, int account) {
        if (isDownloading || TextUtils.isEmpty(latestApkUrl)) return;
        isDownloading = true;
        isDownloaded = false;
        downloadProgress = 0f;

        String fileName = org.telegram.messenger.FileLoader.getAttachFileName(document);
        // Качаем в безопасную песочницу, проверяя на null
        File extDir = ApplicationLoader.applicationContext.getExternalFilesDir(null);
        if (extDir == null) {
            isDownloading = false;
            return;
        }
        File destFile = new File(extDir, "PixelGram_Update.apk");

        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
        });

        new Thread(() -> {
            try {
                URL url = new URL(latestApkUrl);
                downloadConnection = (HttpURLConnection) url.openConnection();
                downloadConnection.setInstanceFollowRedirects(true);
                downloadConnection.setConnectTimeout(15000);
                downloadConnection.setReadTimeout(30000);
                downloadConnection.connect();

                if (downloadConnection.getResponseCode() == HttpURLConnection.HTTP_MOVED_TEMP ||
                        downloadConnection.getResponseCode() == HttpURLConnection.HTTP_MOVED_PERM ||
                        downloadConnection.getResponseCode() == HttpURLConnection.HTTP_SEE_OTHER) {
                    String redirectUrl = downloadConnection.getHeaderField("Location");
                    downloadConnection = (HttpURLConnection) new URL(redirectUrl).openConnection();
                    downloadConnection.setConnectTimeout(15000);
                    downloadConnection.setReadTimeout(30000);
                    downloadConnection.connect();
                }

                int totalSize = downloadConnection.getContentLength();
                downloadStream = downloadConnection.getInputStream();
                java.io.FileOutputStream output = new java.io.FileOutputStream(destFile);

                byte[] data = new byte[8192];
                long downloaded = 0;
                int count;
                long lastTime = System.currentTimeMillis();

                while ((count = downloadStream.read(data)) != -1) {
                    if (!isDownloading) {
                        output.close();
                        downloadStream.close();
                        destFile.delete();
                        return;
                    }
                    downloaded += count;
                    output.write(data, 0, count);

                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastTime > 40) {
                        lastTime = currentTime;
                        Long loadedSize = downloaded;
                        Long totalSizeLong = (long) totalSize;

                        // Защита от отрицательного прогресса
                        if (totalSize > 0) {
                            downloadProgress = (float) downloaded / totalSize;
                        } else {
                            downloadProgress = 0f; // Indeterminate не поддерживается UI Телеги
                        }

                        AndroidUtilities.runOnUIThread(() -> {
                            NotificationCenter.getInstance(account).postNotificationName(
                                    NotificationCenter.fileLoadProgressChanged,
                                    fileName, loadedSize, totalSizeLong
                            );
                        });
                    }
                }
                output.flush();
                output.close();
                downloadStream.close();

                isDownloading = false;
                isDownloaded = true;
                downloadProgress = 1f;

                AndroidUtilities.runOnUIThread(() -> {
                    // Файл загружен. Больше не вызываем автоустановку! Ждем клика "Update Now"
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoaded, fileName, destFile);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                });

            } catch (Exception e) {
                FileLog.e(e);
                isDownloading = false;
                downloadProgress = 0f;
                if (destFile.exists()) destFile.delete();
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoadFailed, fileName, 0);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);

                    BulletinFactory.global().createSimpleBulletin(
                            org.telegram.messenger.R.raw.error, "Ошибка загрузки обновления"
                    ).show();
                });
            } finally {
                try {
                    if (downloadStream != null) downloadStream.close();
                    if (downloadConnection != null) downloadConnection.disconnect();
                } catch (Exception ignore) {
                }
                downloadStream = null;
                downloadConnection = null;
            }
        }).start();
    }

    public static void cancelDownload() {
        isDownloading = false;
        try {
            if (downloadStream != null) downloadStream.close();
            if (downloadConnection != null) downloadConnection.disconnect();
        } catch (Exception ignore) {
        }
        downloadStream = null;
        downloadConnection = null;
    }

    // Собственный безопасный метод установки
    public static void installApk(Activity activity) {
        try {
            File extDir = ApplicationLoader.applicationContext.getExternalFilesDir(null);
            if (extDir == null) return;
            File f = new File(extDir, "PixelGram_Update.apk");
            if (!f.exists()) return;

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

            if (Build.VERSION.SDK_INT >= 24) {
                intent.setDataAndType(androidx.core.content.FileProvider.getUriForFile(
                                activity, ApplicationLoader.getApplicationId() + ".provider", f),
                        "application/vnd.android.package-archive");
            } else {
                intent.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
            }
            activity.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // Метод для умного сравнения версий (например, 10.14.1 > 10.14.0.5)
    private static int compareVersions(String v1, String v2) {
        try {
            String[] parts1 = v1.replaceAll("[^0-9.]", "").split("\\.");
            String[] parts2 = v2.replaceAll("[^0-9.]", "").split("\\.");
            int max = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < max; i++) {
                int p1 = i < parts1.length && !parts1[i].isEmpty() ? Integer.parseInt(parts1[i]) : 0;
                int p2 = i < parts2.length && !parts2[i].isEmpty() ? Integer.parseInt(parts2[i]) : 0;
                if (p1 < p2) return -1;
                if (p1 > p2) return 1;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0; // Версии равны
    }
}