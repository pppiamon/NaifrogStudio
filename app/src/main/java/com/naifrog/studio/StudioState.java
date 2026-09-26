package com.naifrog.studio;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.util.Base64;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

final class StudioState {
    @android.annotation.SuppressLint("StaticFieldLeak") // Holds the application context, never an Activity.
    private static StudioState instance;
    static synchronized StudioState get(Context context) { if (instance == null) instance = new StudioState(context.getApplicationContext()); return instance; }
    final Context context;
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final ExecutorService control = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    final SharedPreferences preferences;
    final CodexAccount account;
    final ServiceConnection connection;
    final File references, creations, draft;
    Bitmap original, selection, result;
    File selectedReference, resultFile;
    List<Rect> faces = new ArrayList<>();
    int selectedFace;
    volatile boolean busy, detecting;
    volatile boolean cancelRequested;
    String status = "导入照片，开始你的奶蛙变身", error = "";
    Runnable listener;
    final FaceDetector detector;

    private StudioState(Context ctx) {
        context = ctx; preferences = ctx.getSharedPreferences("studio", Context.MODE_PRIVATE);
        connection = new ServiceConnection(ctx, preferences); connection.installPackagedProfile();
        account = new CodexAccount(this);
        references = new File(ctx.getFilesDir(), "references"); references.mkdirs();
        creations = new File(ctx.getFilesDir(), "creations"); creations.mkdirs();
        draft = new File(ctx.getFilesDir(), "draft"); draft.mkdirs();
        detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setMinFaceSize(.04f).build());
        io.execute(() -> {
            try {
                String[] assets = ctx.getAssets().list("references");
                if (assets != null) for (String name : assets) if (name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|webp)$")) {
                    File target = new File(references, name);
                    if (!target.exists() && !preferences.getBoolean("removed_" + name, false)) {
                        try (InputStream in = ctx.getAssets().open("references/" + name); OutputStream out = new FileOutputStream(target)) { MainActivity.copy(in, out); }
                    }
                }
                String chosen = preferences.getString("reference", "");
                selectedReference = new File(references, chosen);
                if (!selectedReference.isFile()) {
                    File front = new File(references, "01_front.png");
                    selectedReference = front.isFile() ? front : referenceFiles().isEmpty() ? null : referenceFiles().get(0);
                }
                File source = new File(draft, "original.png");
                if (source.exists()) {
                    Bitmap loaded = BitmapFactory.decodeFile(source.getPath());
                    Bitmap mask = BitmapFactory.decodeFile(new File(draft, "selection.png").getPath());
                    main.post(() -> { original = loaded; selection = mask; if (original != null) detect(false); });
                }
            } catch (Exception e) { error = e.getMessage(); }
            notifyUi();
        });
    }
    void notifyUi() { main.post(() -> { if (listener != null) listener.run(); }); }
    List<File> referenceFiles() {
        List<File> files = imageFiles(references);
        files.sort(Comparator.comparing((File f) -> !f.equals(selectedReference)).thenComparing(File::getName));
        return files;
    }
    List<File> creations() { return imageFiles(creations); }
    private List<File> imageFiles(File folder) {
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|webp)$"));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed()); return new ArrayList<>(Arrays.asList(files));
    }
    String serviceUrl() { return preferences.getString("url", BuildConfig.SERVICE_URL).replaceAll("/+$", ""); }
    String provider() { return preferences.getString("provider", "codex"); }
    boolean hasPending() { return !preferences.getString("job", "").isEmpty(); }
    void chooseReference(File file) { selectedReference = file; preferences.edit().putString("reference", file.getName()).apply(); notifyUi(); }
    void importPhoto(Uri uri) {
        busy = true; error = ""; status = "正在读取照片…"; notifyUi();
        io.execute(() -> {
            try {
                Bitmap photo = Images.load(context, uri, 2048);
                if (Math.min(photo.getWidth(), photo.getHeight()) < 64) throw new IOException("请选择尺寸大于 64 像素的照片");
                Images.save(photo, new File(draft, "original.png"));
                new File(draft, "selection.png").delete();
                main.post(() -> { original = photo; selection = null; result = null; resultFile = null; busy = false; detect(true); });
            } catch (Exception e) { fail("读取照片失败：" + e.getMessage()); }
        });
    }
    void importReferences(List<Uri> uris) {
        busy = true; error = ""; status = "正在导入角色参考图…"; notifyUi();
        io.execute(() -> {
            int count = 0; String lastError = "";
            for (Uri uri : uris) {
                try {
                    Bitmap image = Images.load(context, uri, 1536);
                    File out = new File(references, "奶蛙_" + UUID.randomUUID().toString().substring(0, 8) + ".png");
                    Images.save(image, out); image.recycle(); selectedReference = out; count++;
                } catch (Exception e) { lastError = e.getMessage(); }
            }
            if (selectedReference != null) preferences.edit().putString("reference", selectedReference.getName()).apply();
            status = "已导入 " + count + " 张参考图"; error = lastError; busy = false; notifyUi();
        });
    }
    void detect(boolean resetMask) {
        if (original == null) return;
        final Bitmap photo = original;
        detecting = true; faces.clear(); error = ""; status = "正在识别人像…"; notifyUi();
        detector.process(InputImage.fromBitmap(photo, 0)).addOnSuccessListener(found -> {
            if (original != photo) return;
            detecting = false; faces = new ArrayList<>();
            for (Face face : found) faces.add(new Rect(face.getBoundingBox()));
            faces.sort(Comparator.comparingInt(r -> r.left));
            selectedFace = Math.min(selectedFace, Math.max(0, faces.size() - 1));
            if (faces.isEmpty()) { status = "已进入手动选区 · 框选或涂抹角色面部，也可补选身体"; }
            else { status = "检测到 " + faces.size() + " 位人物 · 已选第 " + (selectedFace + 1) + " 位"; if (resetMask) selection = null; }
            notifyUi();
        }).addOnFailureListener(e -> {
            if (original != photo) return;
            detecting = false; status = "已进入手动选区 · 框选或涂抹需要转换的角色"; notifyUi();
        });
    }
    void fail(String message) { error = message; busy = false; notifyUi(); }
    void persistSelection() {
        if (selection == null) return;
        Bitmap snapshot = selection.copy(Bitmap.Config.ARGB_8888, false);
        io.execute(() -> { try { Images.save(snapshot, new File(draft, "selection.png")); } catch (Exception ignored) { } finally { snapshot.recycle(); } });
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Persist job receipt on the IO executor before upload.
    void generate() {
        if (busy) return;
        busy = true; cancelRequested = false; error = ""; status = hasPending() ? "正在恢复生成任务…" : "正在上传原图与奶蛙参考图…"; notifyUi();
        io.execute(() -> {
            try {
                String id = preferences.getString("job", "");
                if (id.isEmpty()) {
                    if (original == null || selectedReference == null || selection == null) throw new IOException("请准备照片、参考图和选区");
                    if (!Images.hasSelection(selection)) throw new IOException("请框选或涂抹需要替换的区域");
                    id = UUID.randomUUID().toString();
                    JSONObject payload = new JSONObject(); payload.put("requestId", id);
                    payload.put("provider", provider());
                    payload.put("original", Base64.encodeToString(Images.bytes(original), Base64.NO_WRAP));
                    payload.put("reference", Base64.encodeToString(Files.readAllBytes(selectedReference.toPath()), Base64.NO_WRAP));
                    payload.put("selection", Base64.encodeToString(Images.bytes(selection), Base64.NO_WRAP));
                    Files.write(new File(draft, "request.json").toPath(), payload.toString().getBytes(StandardCharsets.UTF_8));
                    Images.save(selection, new File(draft, "selection.png"));
                    preferences.edit().putString("job", id).putString("jobUrl", serviceUrl()).putString("jobToken", preferences.getString("token", ""))
                            .putString("jobSession", provider().equals("codex") ? account.token() : "").commit();
                }
                String url = preferences.getString("jobUrl", serviceUrl());
                String token = preferences.getString("jobToken", "");
                String session = preferences.getString("jobSession", "");
                // Submission is idempotent, including after a dropped upload response or app restart.
                byte[] payload = Files.readAllBytes(new File(draft, "request.json").toPath());
                jsonRequest(url + "/v1/jobs", "POST", payload, token, session);
                long deadline = System.currentTimeMillis() + 660000;
                while (System.currentTimeMillis() < deadline) {
                    JSONObject job;
                    try { job = jsonRequest(url + "/v1/jobs/" + id, "GET", null, token, session); }
                    catch (IOException disconnected) {
                        if (disconnected instanceof HttpError && ((HttpError)disconnected).code < 500) throw disconnected;
                        status = "网络正在恢复，作品仍在生成，正在重新连接…"; notifyUi(); Thread.sleep(3000); continue;
                    }
                    String state = job.getString("status");
                    if (state.equals("completed")) {
                        byte[] bytes = request(url + "/v1/jobs/" + id + "/result", "GET", null, token, session);
                        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (image == null) throw new IOException("返回图片无法读取");
                        File out = new File(creations, "奶蛙_" + System.currentTimeMillis() + ".png");
                        Images.save(image, out); clearJob();
                        main.post(() -> { result = image; resultFile = out; busy = false; status = "你的奶蛙照片已完成"; notifyUi(); }); return;
                    }
                    if (state.equals("cancelled")) { finishCancel(); return; }
                    if (state.equals("failed")) {
                        clearJob(); throw new IOException(job.optString("error", "任务已取消"));
                    }
                    String next = state.equals("queued") ? "正在排队，完成后将自动显示…" : "正在变成奶蛙，通常需要 1–3 分钟…";
                    if (!next.equals(status)) { status = next; notifyUi(); }
                    Thread.sleep(2500);
                }
                throw new IOException("任务仍在处理中，点击继续获取结果");
            } catch (Exception e) {
                if (cancelRequested) { finishCancel(); return; }
                fail((hasPending() ? "可继续获取结果：" : "生成失败：") + e.getMessage());
            }
        });
    }
    @android.annotation.SuppressLint("ApplySharedPref")
    void clearJob() { preferences.edit().remove("job").remove("jobUrl").remove("jobToken").remove("jobSession").commit(); new File(draft, "request.json").delete(); }
    void discardPending() {
        if (!hasPending() || cancelRequested) return;
        final boolean wasBusy = busy;
        cancelRequested = true; status = "正在取消任务…"; notifyUi();
        control.execute(() -> {
            try {
                JSONObject job = jsonRequest(preferences.getString("jobUrl", serviceUrl()) + "/v1/jobs/" + preferences.getString("job", "") + "/cancel", "POST", new byte[0], preferences.getString("jobToken", ""), preferences.getString("jobSession", ""));
                if (job.optString("status").equals("completed")) { cancelRequested = false; if (!wasBusy) { busy = false; generate(); } }
                else if (!wasBusy) finishCancel();
            } catch (Exception e) {
                if (e instanceof HttpError && ((HttpError)e).code == 404) { if (!wasBusy) finishCancel(); }
                else { cancelRequested = false; error = "取消失败：" + e.getMessage(); notifyUi(); }
            }
        });
    }
    void finishCancel() { clearJob(); busy = false; error = ""; status = "已取消生成任务"; notifyUi(); }
    void forgetPending() {
        if (busy) return;
        preferences.edit().putString("lastJob", preferences.getString("job", "")).apply();
        io.execute(() -> { clearJob(); error = ""; status = "已清除本地等待记录，可以开始新创作"; notifyUi(); });
    }
    static final class HttpError extends IOException { final int code; HttpError(int code, String message) { super(message); this.code = code; } }
    static JSONObject jsonRequest(String url, String method, byte[] body, String token) throws Exception {
        return new JSONObject(new String(request(url, method, body, token), StandardCharsets.UTF_8));
    }
    static JSONObject jsonRequest(String url, String method, byte[] body, String token, String session) throws Exception {
        return new JSONObject(new String(request(url, method, body, token, session), StandardCharsets.UTF_8));
    }
    static byte[] request(String url, String method, byte[] body, String token) throws Exception {
        return request(url, method, body, token, "");
    }
    static byte[] request(String url, String method, byte[] body, String token, String session) throws Exception {
        if (instance != null && (!instance.preferences.getString("connectionId", "").isEmpty() || url.startsWith("https://"))) return instance.connection.request(url, method, body, token, session);
        URL target = new URL(url);
        HttpURLConnection connection = (HttpURLConnection)(isLocalServiceHost(target.getHost())
                ? target.openConnection(Proxy.NO_PROXY) : target.openConnection());
        try {
            connection.setRequestMethod(method); connection.setConnectTimeout(20000); connection.setReadTimeout(90000);
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (!session.isEmpty()) connection.setRequestProperty("X-Codex-Session", session);
            if (body != null) { connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(body.length); try (OutputStream out = connection.getOutputStream()) { out.write(body); } }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            byte[] data; try (InputStream in = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (in != null) MainActivity.copy(in, bytes); data = bytes.toByteArray();
            }
            if (status < 200 || status >= 300) {
                String message = "连接失败（" + status + "）";
                try { message = new JSONObject(new String(data, StandardCharsets.UTF_8)).optString("error", message); } catch (JSONException ignored) { }
                throw new HttpError(status, message);
            }
            return data;
        } catch (HttpError e) { throw e;
        } catch (IOException e) {
            String address = target.getProtocol() + "://" + target.getAuthority();
            String hint = isLocalServiceHost(target.getHost())
                    ? "请在电脑上双击“启动奶蛙试用服务”，手机与电脑连接同一 Wi-Fi，并按启动窗口核对地址。"
                    : "请核对生成服务地址，并检查当前网络连接。";
            throw new IOException("无法连接生成服务：" + address + "\n" + hint, e);
        } finally { connection.disconnect(); }
    }
    static boolean isLocalServiceHost(String host) {
        if ("localhost".equalsIgnoreCase(host) || "[::1]".equals(host) || "::1".equals(host)) return true;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        int[] ip = new int[4];
        try { for (int i = 0; i < 4; i++) { ip[i] = Integer.parseInt(parts[i]); if (ip[i] < 0 || ip[i] > 255) return false; } }
        catch (NumberFormatException e) { return false; }
        return ip[0] == 10 || ip[0] == 127 || (ip[0] == 192 && ip[1] == 168)
                || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)
                || (ip[0] == 100 && ip[1] >= 64 && ip[1] <= 127)
                || (ip[0] == 169 && ip[1] == 254);
    }
}
