package ru.slavapmk.pixelgram;

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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class PixelGramUpdater {
    public static String latestApkUrl = "";

    public interface UpdateCallback {
        void onResult(TLRPC.TL_help_appUpdate update, boolean hasUpdate);
        void onError();
    }

    public static void check(UpdateCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                // ВАЖНО: Замени "ТВОЙ_НИК" на свой логин GitHub
                URL url = new URL("https://api.github.com/repos/slavapmk/PixelGram/releases/latest");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

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
                    if (assets.length() > 0) {
                        JSONObject asset = assets.getJSONObject(0);
                        latestApkUrl = asset.getString("browser_download_url");
                        size = asset.getLong("size");
                    }

                    int remoteVersionCode = 0;
                    try {
                        String[] parts = tagName.split("-");
                        if (parts.length > 1) {
                            remoteVersionCode = Integer.parseInt(parts[parts.length - 1]);
                        }
                    } catch (Exception e) {
                        FileLog.e("PixelGramUpdater: Ошибка парсинга тега " + tagName);
                    }

                    // Сравниваем тег с текущей версией (из BuildVars)
                    int currentVersionCode = 0;
                    try {
                        android.content.pm.PackageInfo pInfo = ApplicationLoader.applicationContext
                                .getPackageManager()
                                .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                        currentVersionCode = pInfo.versionCode;
                    } catch (Exception e) {
                        FileLog.e(e);
                    }

                    // Если версии не совпадают — генерируем фейковый апдейт для UI
                    if (remoteVersionCode > currentVersionCode && remoteVersionCode != 0) {
                        TLRPC.TL_help_appUpdate update = new TLRPC.TL_help_appUpdate();
                        update.version = tagName.split("-")[0].replace("v", ""); // Оставляем только "10.14.0" для UI
                        update.text = body;
                        update.can_not_skip = false;

                        update.document = new TLRPC.TL_document();
                        update.document.size = size;

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
    private static Thread downloadThread;

    public static void startDownload(TLRPC.Document document, int account) {
        if (isDownloading || android.text.TextUtils.isEmpty(latestApkUrl)) return;
        isDownloading = true;
        downloadProgress = 0f;

        String fileName = org.telegram.messenger.FileLoader.getAttachFileName(document);
        // Сохраняем ровно туда, где файл будет искать openApkInstall
        java.io.File destFile = org.telegram.messenger.FileLoader.getInstance(account).getPathToAttach(document, true);

        // Перерисовываем UI (кнопки поменяются на "Отмена")
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
        });

        downloadThread = new Thread(() -> {
            try {
                URL url = new URL(latestApkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                // GitHub Releases всегда редиректят скачивание на AWS-сервера
                if (conn.getResponseCode() == HttpURLConnection.HTTP_MOVED_TEMP ||
                        conn.getResponseCode() == HttpURLConnection.HTTP_MOVED_PERM ||
                        conn.getResponseCode() == HttpURLConnection.HTTP_SEE_OTHER) {
                    String redirectUrl = conn.getHeaderField("Location");
                    conn = (HttpURLConnection) new URL(redirectUrl).openConnection();
                    conn.connect();
                }

                int totalSize = conn.getContentLength();
                java.io.InputStream input = conn.getInputStream();
                java.io.FileOutputStream output = new java.io.FileOutputStream(destFile);

                byte[] data = new byte[8192];
                long downloaded = 0;
                int count;
                long lastTime = System.currentTimeMillis();

                while ((count = input.read(data)) != -1) {
                    if (!isDownloading) {
                        output.close();
                        input.close();
                        destFile.delete();
                        return;
                    }
                    downloaded += count;
                    output.write(data, 0, count);

                    long currentTime = System.currentTimeMillis();
                    // Шлем события для UI каждые 40мс (≈25 FPS для плавного кружочка)
                    if (currentTime - lastTime > 40) {
                        lastTime = currentTime;
                        Long loadedSize = downloaded;
                        Long totalSizeLong = (long) totalSize;
                        downloadProgress = (float) downloaded / totalSize;

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
                input.close();

                isDownloading = false;
                downloadProgress = 1f;

                AndroidUtilities.runOnUIThread(() -> {
                    // Файл загружен, обновляем UI на "Update Now"
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoaded, fileName, destFile);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);

                    // Автоматически триггерим системное окно установки
                    if (org.telegram.ui.LaunchActivity.instance != null) {
                        ApplicationLoader.applicationLoaderInstance.openApkInstall(org.telegram.ui.LaunchActivity.instance, document);
                    }
                });

            } catch (Exception e) {
                FileLog.e(e);
                isDownloading = false;
                downloadProgress = 0f;
                if (destFile.exists()) destFile.delete();
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoadFailed, fileName, 0);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                });
            }
        });
        downloadThread.start();
    }

    public static void cancelDownload() {
        isDownloading = false;
    }
}
