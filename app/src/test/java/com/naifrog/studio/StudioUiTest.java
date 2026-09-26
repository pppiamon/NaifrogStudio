package com.naifrog.studio;

import android.graphics.*;
import android.view.*;
import android.widget.TextView;
import android.os.Looper;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, qualifiers="zh-rCN-w360dp-h760dp-xhdpi", shadows={StudioUiTest.UiOnlyDetector.class, StudioUiTest.BoundNetwork.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class StudioUiTest {
    // Android's network-bound DNS is native. Exercise routing with real local HTTP in JVM tests.
    @Implements(android.net.Network.class)
    public static class BoundNetwork {
        @Implementation protected java.net.URLConnection openConnection(java.net.URL url, java.net.Proxy proxy) throws IOException { return url.openConnection(proxy); }
    }
    // Detection itself belongs to the device instrumented tests. These tests exercise UI and masks.
    @Implements(value=com.google.mlkit.vision.face.FaceDetection.class, isInAndroidSdk=false)
    public static class UiOnlyDetector {
        static boolean failDetection;
        @Implementation public static com.google.mlkit.vision.face.FaceDetector getClient(com.google.mlkit.vision.face.FaceDetectorOptions options) { return detector(); }
        @Implementation public static com.google.mlkit.vision.face.FaceDetector getClient() { return detector(); }
        private static com.google.mlkit.vision.face.FaceDetector detector() {
            return (com.google.mlkit.vision.face.FaceDetector)java.lang.reflect.Proxy.newProxyInstance(
                com.google.mlkit.vision.face.FaceDetector.class.getClassLoader(), new Class[]{com.google.mlkit.vision.face.FaceDetector.class},
                (proxy, method, arguments) -> method.getName().equals("process")
                        ? failDetection ? com.google.android.gms.tasks.Tasks.forException(new IOException("test detector unavailable")) : com.google.android.gms.tasks.Tasks.forResult(Collections.emptyList()) : null);
        }
    }
    ActivityController<MainActivity> controller;
    MainActivity activity;
    @Before public void launch() throws Exception {
        UiOnlyDetector.failDetection = false;
        RuntimeEnvironment.getApplication().getSharedPreferences("studio", android.content.Context.MODE_PRIVATE).edit().clear().commit();
        com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication());
        java.lang.reflect.Field instance = StudioState.class.getDeclaredField("instance"); instance.setAccessible(true); instance.set(null, null);
        controller = Robolectric.buildActivity(MainActivity.class).setup(); activity = controller.get();
        activity.state.connection.clearProfile();
        activity.state.io.submit(() -> {}).get(); Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @After public void close() {
        if (controller != null) controller.pause().stop().destroy();
        if (activity != null) { activity.state.main.removeCallbacksAndMessages(null); activity.state.detector.close(); activity.state.io.shutdown(); activity.state.control.shutdown(); }
    }
    private void render(String name) throws Exception {
        View decor = activity.getWindow().getDecorView();
        File font = new File("C:/Windows/Fonts/msyh.ttc");
        if (font.exists()) applyFont(decor, Typeface.createFromFile(font));
        decor.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1520, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 720, 1520);
        if (name.contains("controls")) activity.scroll.scrollTo(0, activity.content.getHeight());
        Bitmap bitmap = Bitmap.createBitmap(720, 1520, Bitmap.Config.ARGB_8888); decor.draw(new Canvas(bitmap));
        File folder = new File("build/qa"); folder.mkdirs(); Images.save(bitmap, new File(folder, name + ".png"));
    }
    private void applyFont(View view, Typeface font) {
        if (view instanceof TextView text) text.setTypeface(font, text.getTypeface() == null ? Typeface.NORMAL : text.getTypeface().getStyle());
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) applyFont(group.getChildAt(i), font);
    }
    private void captureDialog(android.app.AlertDialog dialog, String name) throws Exception {
        View decor = dialog.getWindow().getDecorView(); File font = new File("C:/Windows/Fonts/msyh.ttc");
        if (font.exists()) applyFont(decor, Typeface.createFromFile(font));
        decor.measure(View.MeasureSpec.makeMeasureSpec(680, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, 680, decor.getMeasuredHeight());
        Bitmap image = Bitmap.createBitmap(680, decor.getHeight(), Bitmap.Config.ARGB_8888); decor.draw(new Canvas(image));
        File directory = new File("build/qa"); directory.mkdirs(); Images.save(image, new File(directory, name + ".png"));
    }
    @Test public void homeLibraryAndGallery() throws Exception {
        assertEquals("Import error=" + activity.state.error + "; assets=" + Arrays.toString(activity.getAssets().list("references")), 12, activity.state.referenceFiles().size());
        assertNotNull(activity.state.selectedReference);
        assertEquals(activity.state.selectedReference, activity.state.referenceFiles().get(0));
        render("01-home-layout");
        activity.tab = 1; activity.render(); render("02-library-layout");
        activity.tab = 2; activity.render(); render("03-gallery-layout");
    }
    @Test public void maskPaintingUndoAndImmutableDraft() throws Exception {
        Bitmap source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Bitmap immutable = source.copy(Bitmap.Config.ARGB_8888, false);
        EditCanvas editor = new EditCanvas(activity); editor.setImage(source, immutable);
        android.widget.FrameLayout container = new android.widget.FrameLayout(activity); container.addView(editor);
        editor.layout(0, 0, 512, 512); editor.draw(new Canvas(source.copy(Bitmap.Config.ARGB_8888, true)));
        editor.clearSelection(); editor.mode = 1;
        editor.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 256, 256, 0));
        editor.onTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, 256, 256, 0));
        assertTrue(Images.hasSelection(editor.getMask()));
        editor.undo(); assertFalse(Images.hasSelection(editor.getMask()));
        editor.selectFace(new Rect(140, 100, 370, 380));
        assertTrue(Color.alpha(editor.getMask().getPixel(255, 250)) > 200);
        assertEquals(0, Color.alpha(editor.getMask().getPixel(0, 0)));
    }
    @Test public void editorAndResultLayouts() throws Exception {
        File fixture = new File("src/androidTest/assets/astronaut.png");
        Bitmap portrait = BitmapFactory.decodeFile(fixture.getAbsolutePath()); assertNotNull(portrait);
        activity.state.original = portrait; activity.state.faces = new ArrayList<>(List.of(new Rect(171, 63, 278, 179)));
        activity.state.selection = null; activity.state.status = "检测到 1 位人物 · 已选第 1 位";
        activity.tab = 0; activity.render(); render("04-editor-layout");
        activity.editor.clearSelection(); activity.editor.mode = 1;
        activity.editor.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, activity.editor.getWidth()/2f, activity.editor.getHeight()/2f, 0));
        activity.editor.onTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, activity.editor.getWidth()/2f, activity.editor.getHeight()/2f, 0));
        assertTrue(Images.hasSelection(activity.editor.getMask()));
        activity.render(); activity.editor.undo(); assertFalse(Images.hasSelection(activity.editor.getMask()));
        activity.editor.selectFace(activity.state.faces.get(0));
        activity.scroll.scrollTo(0, activity.content.getHeight()); render("05-editor-controls-layout");
    }
    @Test public void validatesServiceUrls() {
        assertTrue(MainActivity.validUrl("http://192.168.1.2:8787"));
        assertTrue(MainActivity.validUrl("https://example.com/naifrog"));
        assertFalse(MainActivity.validUrl("example.com"));
        assertFalse(MainActivity.validUrl("https://example.com/#token"));
    }
    @Test public void codexAccountLoginStatesAndServiceSwitch() throws Exception {
        activity.state.preferences.edit().putString("url", "https://studio.example").putString("provider", "codex")
                .putString("codexUrl", "https://studio.example").putString("codexSession", "test-session").apply();
        CodexAccount account = activity.state.account;
        assertEquals("test-session", account.token());
        activity.tab = 3; activity.render(); render("06-codex-account");
        account.apply(new org.json.JSONObject("{\"authenticated\":false,\"login\":{\"state\":\"pending\",\"userCode\":\"ABCD-1234\",\"verificationUrl\":\"https://auth.openai.com/codex/device\"}}"));
        assertFalse(account.loggedIn); assertEquals("ABCD-1234", account.code);
        activity.render(); render("07-codex-device-code");
        account.apply(new org.json.JSONObject("{\"authenticated\":true,\"account\":{\"email\":\"portrait@example.com\",\"planType\":\"plus\"}}"));
        assertTrue(account.loggedIn); assertEquals("", account.code); assertEquals("plus", account.plan);
        activity.render(); render("08-codex-signed-in");
        activity.state.preferences.edit().putString("url", "https://another.example").apply();
        assertEquals("", account.token()); account.changedService(); assertFalse(account.loggedIn);
        account.apply(new org.json.JSONObject("{\"authenticated\":false,\"login\":{\"state\":\"failed\",\"error\":\"验证码已过期\"}}"));
        assertEquals("验证码已过期", account.error); assertEquals("", account.code);
        activity.state.preferences.edit().remove("codexSession").remove("url").apply();
    }
    private android.widget.Button findButton(View view, String label) {
        if (view instanceof android.widget.Button button && label.equals(button.getText().toString())) return button;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            android.widget.Button found = findButton(group.getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private void drag(EditCanvas canvas, float x1, float y1, float x2, float y2, int finish) {
        canvas.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x1, y1, 0));
        canvas.onTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE, x2, y2, 0));
        canvas.onTouchEvent(MotionEvent.obtain(0, 20, finish, x2, y2, 0));
    }
    static class LocalImageServer implements AutoCloseable {
        final java.net.ServerSocket socket = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final java.util.concurrent.atomic.AtomicReference<org.json.JSONObject> submitted = new java.util.concurrent.atomic.AtomicReference<>();
        volatile Exception failure;
        final Thread worker;
        LocalImageServer(byte[] image) throws IOException {
            worker = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (java.net.Socket client = socket.accept()) {
                        InputStream in = client.getInputStream();
                        ByteArrayOutputStream header = new ByteArrayOutputStream();
                        int end = 0, value;
                        while ((value = in.read()) != -1) { header.write(value); end = (end << 8) | value; if (end == 0x0d0a0d0a) break; }
                        String text = header.toString(java.nio.charset.StandardCharsets.UTF_8);
                        int length = 0;
                        for (String line : text.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                        byte[] request = in.readNBytes(length);
                        if (text.startsWith("POST ")) submitted.set(new org.json.JSONObject(new String(request, java.nio.charset.StandardCharsets.UTF_8)));
                        byte[] body = text.split("\r\n")[0].contains("/result ") ? image : "{\"status\":\"completed\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        out.write(body); out.flush();
                    } catch (Exception e) { if (!socket.isClosed()) { failure = e; break; } }
                }
            });
            worker.setDaemon(true); worker.start();
        }
        @Override public void close() throws IOException { socket.close(); }
    }
    @Test public void boxSelectionStartsWithoutFaceAndSupportsUndoAndCancel() {
        Bitmap source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        EditCanvas canvas = new EditCanvas(activity); canvas.setImage(source, null);
        android.widget.FrameLayout parent = new android.widget.FrameLayout(activity); parent.addView(canvas);
        canvas.layout(0, 0, 256, 256); canvas.draw(new Canvas(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)));
        assertNotNull(canvas.getMask()); assertFalse(Images.hasSelection(canvas.getMask()));
        canvas.mode = 3;
        drag(canvas, 180, 180, 40, 40, MotionEvent.ACTION_UP);
        assertEquals(255, Color.alpha(canvas.getMask().getPixel(50, 50)));
        assertEquals(0, Color.alpha(canvas.getMask().getPixel(10, 10)));
        canvas.undo(); assertFalse(Images.hasSelection(canvas.getMask()));
        drag(canvas, 40, 40, 200, 200, MotionEvent.ACTION_CANCEL);
        assertFalse(Images.hasSelection(canvas.getMask()));
        drag(canvas, 40, 40, 200, 200, MotionEvent.ACTION_UP);
        canvas.mode = 2; drag(canvas, 128, 128, 128, 128, MotionEvent.ACTION_UP);
        assertEquals(0, Color.alpha(canvas.getMask().getPixel(64, 64)));
        canvas.undo(); assertEquals(255, Color.alpha(canvas.getMask().getPixel(64, 64)));
        canvas.clearSelection(); assertFalse(Images.hasSelection(canvas.getMask()));
    }
    @Test public void missingFacePreservesDraftAndAllowsManualGenerationOverHttp() throws Exception {
        activity.state.original = BitmapFactory.decodeFile(activity.state.selectedReference.getAbsolutePath());
        activity.state.selection = null; activity.state.result = null; activity.state.detect(true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(activity.state.faces.isEmpty()); assertFalse(activity.state.detecting);
        assertTrue(activity.state.status.contains("手动选区"));
        activity.tab = 0; activity.render(); render("09-manual-selection");
        assertEquals(3, activity.editor.mode); assertNotNull(findButton(activity.root, "框选"));
        assertNotNull(findButton(activity.root, "涂抹"));
        android.widget.Button generate = findButton(activity.root, "✦  生成奶蛙照片");
        assertNotNull(generate); assertTrue(generate.isEnabled());
        float w = activity.editor.getWidth(), h = activity.editor.getHeight();
        drag(activity.editor, w * .25f, h * .25f, w * .75f, h * .65f, MotionEvent.ACTION_UP);
        assertTrue(Images.hasSelection(activity.state.selection));
        Bitmap selected = activity.state.selection;
        activity.state.detect(false); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(selected, activity.state.selection); assertTrue(Images.hasSelection(activity.state.selection));
        activity.render(); render("10-manual-controls");
        final byte[] image = Images.bytes(activity.state.original);
        try (LocalImageServer http = new LocalImageServer(image)) {
            activity.state.preferences.edit().putString("url", "http://127.0.0.1:" + http.socket.getLocalPort()).putString("provider", "api").apply();
            findButton(activity.root, "✦  生成奶蛙照片").performClick();
            activity.state.io.submit(() -> {}).get(15, java.util.concurrent.TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(http.failure);
            assertNotNull("Generation error: " + activity.state.error, http.submitted.get());
            assertEquals("api", http.submitted.get().getString("provider"));
            assertTrue(http.submitted.get().getString("selection").length() > 50);
            assertNotNull(activity.state.result); assertFalse(activity.state.hasPending());
            assertTrue(activity.state.resultFile.isFile());
        }
    }
    @Test public void failedDetectorFallsBackWithoutClearingExistingMask() throws Exception {
        activity.state.original = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        activity.state.selection = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        activity.state.selection.setPixel(64, 64, Color.WHITE);
        Bitmap mask = activity.state.selection;
        UiOnlyDetector.failDetection = true;
        activity.state.detect(false); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(activity.state.detecting); assertTrue(activity.state.status.contains("手动选区"));
        assertSame(mask, activity.state.selection); assertEquals(Color.WHITE, mask.getPixel(64, 64));
    }
    @Test public void lateFaceDetectionReplacesInitialEmptyCanvasWithAutomaticSelection() {
        activity.state.original = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        activity.state.selection = null; activity.state.faces.clear(); activity.state.detecting = true;
        activity.tab = 0; activity.render();
        assertFalse(Images.hasSelection(activity.editor.getMask()));
        activity.state.faces = new ArrayList<>(List.of(new Rect(64, 32, 192, 192)));
        activity.state.selection = null; activity.state.detecting = false;
        activity.render();
        assertTrue(Images.hasSelection(activity.editor.getMask()));
        assertEquals(255, Color.alpha(activity.editor.getMask().getPixel(128, 128)));
    }
    static class LocalLoginServer implements AutoCloseable {
        final java.net.ServerSocket socket = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        volatile boolean authenticated, failLogin;
        volatile String identity = UUID.randomUUID().toString();
        volatile Exception failure;
        LocalLoginServer() throws IOException {
            Thread worker = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (java.net.Socket client = socket.accept()) {
                        InputStream in = client.getInputStream(); ByteArrayOutputStream header = new ByteArrayOutputStream();
                        int end = 0, value;
                        while ((value = in.read()) != -1) { header.write(value); end = (end << 8) | value; if (end == 0x0d0a0d0a) break; }
                        String text = header.toString(java.nio.charset.StandardCharsets.UTF_8);
                        String first = text.split("\r\n")[0]; requests.add(first);
                        int status = 200;
                        String json;
                        if (first.contains("/v1/status ") || first.contains("/health ")) json = "{\"ready\":true,\"serviceId\":\"" + identity + "\",\"providers\":{\"codex\":true}}";
                        else if (first.contains("/v1/codex/login ")) {
                            status = failLogin ? 502 : 200;
                            json = failLogin ? "{\"error\":\"官方登录服务暂未响应\"}" : "{\"sessionToken\":\"fixture-session\",\"userCode\":\"TEST-1234\",\"verificationUrl\":\"https://auth.openai.com/codex/device\"}";
                        } else if (first.contains("/v1/codex/account ") && text.toLowerCase(Locale.ROOT).contains("x-codex-session: fixture-session")) {
                            json = authenticated ? "{\"authenticated\":true,\"account\":{\"email\":\"trial@example.com\",\"planType\":\"plus\"}}"
                                    : "{\"authenticated\":false,\"login\":{\"state\":\"pending\",\"userCode\":\"TEST-1234\",\"verificationUrl\":\"https://auth.openai.com/codex/device\"}}";
                        } else { status = 404; json = "{\"error\":\"Unexpected request\"}"; }
                        byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 " + status + " Result\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        out.write(body); out.flush();
                    } catch (Exception e) { if (!socket.isClosed()) { failure = e; break; } }
                }
            });
            worker.setDaemon(true); worker.start();
        }
        String url() { return "http://127.0.0.1:" + socket.getLocalPort(); }
        @Override public void close() throws IOException { socket.close(); }
    }
    private void awaitAccount() throws Exception {
        activity.state.control.submit(() -> {}).get(15, java.util.concurrent.TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void loginPreflightAndBrowserReturnWorkWithAnUnusableSystemProxy() throws Exception {
        java.net.ProxySelector originalProxy = java.net.ProxySelector.getDefault();
        try (LocalLoginServer http = new LocalLoginServer()) {
            java.net.ProxySelector.setDefault(new java.net.ProxySelector() {
                public List<java.net.Proxy> select(java.net.URI uri) { throw new AssertionError("Local request went through system proxy: " + uri); }
                public void connectFailed(java.net.URI uri, java.net.SocketAddress address, IOException error) { }
            });
            activity.state.preferences.edit().putString("url", http.url()).remove("codexSession").apply();
            CodexAccount account = activity.state.account; account.startLogin(); awaitAccount();
            assertEquals("", account.error); assertEquals("TEST-1234", account.code);
            assertEquals("fixture-session", account.token());
            assertTrue(http.requests.get(0).startsWith("GET /v1/status "));
            assertTrue(http.requests.get(1).startsWith("POST /v1/codex/login "));
            account.refresh(true); awaitAccount(); assertFalse(account.loggedIn);
            http.authenticated = true;
            account.refresh(true); awaitAccount();
            assertTrue(account.loggedIn); assertEquals("trial@example.com", account.email); assertEquals("", account.code);
            assertNull(http.failure);
        } finally { java.net.ProxySelector.setDefault(originalProxy); }
        assertTrue(StudioState.isLocalServiceHost("100.78.125.152"));
        assertFalse(StudioState.isLocalServiceHost("100.128.0.1"));
        assertFalse(StudioState.isLocalServiceHost("api.openai.com"));
    }
    @Test public void stoppedDesktopServiceShowsAddressAndStartupInstruction() throws Exception {
        String address;
        try (java.net.ServerSocket reserved = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            address = "http://127.0.0.1:" + reserved.getLocalPort();
        }
        activity.state.preferences.edit().putString("url", address).remove("codexSession").apply();
        activity.state.account.startLogin(); awaitAccount();
        assertFalse(activity.state.account.busy);
        assertTrue(activity.state.account.error.contains(address));
        assertTrue(activity.state.account.error.contains("启动奶蛙试用服务"));
        assertEquals("", activity.state.account.code);
        activity.tab = 3; activity.render(); render("11-trial-connection-error");
    }
    @Test public void officialLoginFailureIsDistinctFromComputerConnectionFailure() throws Exception {
        try (LocalLoginServer http = new LocalLoginServer()) {
            http.failLogin = true;
            activity.state.preferences.edit().putString("url", http.url()).remove("codexSession").apply();
            activity.state.account.startLogin(); awaitAccount();
            assertEquals("获取 Codex 验证码失败：官方登录服务暂未响应", activity.state.account.error);
            assertEquals(2, http.requests.size()); assertNull(http.failure);
        }
    }
    @Test public void settingsCanReplaceOldAddressWithPackagedTrialAddress() {
        if (BuildConfig.SERVICE_URL.isEmpty()) return;
        activity.state.preferences.edit().putString("url", "http://100.78.87.150:8787").apply();
        activity.settings();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        android.widget.Button preset = findButton(dialog.getWindow().getDecorView(), "填入安装包预设地址");
        assertNotNull(preset); preset.performClick();
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(BuildConfig.SERVICE_URL, activity.state.serviceUrl());
    }
    org.json.JSONObject profile(String id, String... endpoints) throws Exception {
        return new org.json.JSONObject().put("version", 1).put("serviceId", id).put("token", "fixture-token").put("endpoints", new org.json.JSONArray(Arrays.asList(endpoints)));
    }
    @Test public void automaticallyUsesSecondEndpointWhenFirstConnectionIsRefused() throws Exception {
        String offline;
        try (java.net.ServerSocket closed = new java.net.ServerSocket(0)) { offline = "http://127.0.0.1:" + closed.getLocalPort(); }
        try (LocalLoginServer http = new LocalLoginServer()) {
            activity.state.connection.install(profile(http.identity, offline, http.url()));
            activity.state.account.startLogin(); awaitAccount();
            assertEquals("", activity.state.account.error);
            assertEquals("TEST-1234", activity.state.account.code);
            assertTrue(activity.state.connection.lastRoute.startsWith(http.url()));
            assertTrue(http.requests.get(0).contains("/health "));
            http.authenticated = true; activity.state.account.refresh(true); awaitAccount();
            assertTrue(activity.state.account.loggedIn); assertNull(http.failure);
        }
    }
    @Test public void mismatchedServerIdentityNeverReceivesLoginOrCredentials() throws Exception {
        try (LocalLoginServer wrong = new LocalLoginServer()) {
            activity.state.connection.install(profile(UUID.randomUUID().toString(), wrong.url()));
            activity.state.account.startLogin(); awaitAccount();
            assertFalse("Requests=" + wrong.requests + "; busy=" + activity.state.account.busy + "; status=" + activity.state.account.status, activity.state.account.error.isEmpty());
            assertFalse(activity.state.account.loggedIn);
            assertTrue(wrong.requests.stream().allMatch(request -> request.contains("/health ")));
        }
    }
    @Test public void updatedPairingAddressPreservesAccountAndPendingJobForSameServer() throws Exception {
        String identity = UUID.randomUUID().toString();
        activity.state.connection.install(profile(identity, "https://old.example"));
        activity.state.preferences.edit().putString("codexUrl", "https://old.example").putString("codexSession", "fixture-session")
                .putString("job", UUID.randomUUID().toString()).putString("jobUrl", "https://old.example").putString("jobToken", "old-token").apply();
        activity.state.connection.install(profile(identity, "https://new.example"));
        assertEquals("fixture-session", activity.state.account.token());
        assertEquals("https://new.example", activity.state.preferences.getString("jobUrl", ""));
        assertEquals("fixture-token", activity.state.preferences.getString("jobToken", ""));
        try { activity.state.connection.install(profile(UUID.randomUUID().toString(), "https://other.example")); fail("Pending job changed servers"); }
        catch (IOException expected) { assertTrue(activity.state.hasPending()); }
    }
    @Test public void importsEncodedConnectionLinkAndRejectsMalformedAddresses() throws Exception {
        String id = UUID.randomUUID().toString();
        String code = "NAIFROG1." + android.util.Base64.encodeToString(profile(id, "https://photo.example").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        assertEquals(id, ServiceConnection.parse("naifrog://connect?code=" + code).getString("serviceId"));
        activity.importConnection(code); Shadows.shadowOf(Looper.getMainLooper()).idle();
        android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        captureDialog(dialog, "12-connection-import");
        assertEquals(id, ServiceConnection.parse("https://photo.example/pair#" + code).getString("serviceId"));
        dialog.dismiss();
        try (LocalLoginServer http = new LocalLoginServer()) {
            activity.importConnection(ServiceConnection.code(profile(http.identity, http.url())));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); awaitAccount();
            assertEquals(http.url(), activity.state.serviceUrl());
            assertEquals(http.identity, activity.state.preferences.getString("connectionId", ""));
            assertFalse(dialog.isShowing());
        }
        try { ServiceConnection.parse(profile(id, "http://public.example").toString()); fail("Invalid address imported"); }
        catch (IOException expected) { }
        try { ServiceConnection.parse(profile(id, "https://user:pass@public.example").toString()); fail("Invalid address imported"); }
        catch (IOException expected) { }
    }
    @Test public void packagedConnectionReplacesPreviousLanSettingsOnce() throws Exception {
        org.junit.Assume.assumeTrue(Arrays.asList(activity.getAssets().list("")).contains("connection.json"));
        activity.state.preferences.edit().remove("packagedConnection").putString("url", "http://100.78.125.152:8787").apply();
        activity.state.connection.installPackagedProfile();
        assertTrue(activity.state.serviceUrl().startsWith("https://"));
        assertFalse(activity.state.preferences.getString("connectionId", "").isEmpty());
        assertFalse(activity.state.preferences.getString("token", "").isEmpty());
        activity.settings(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        captureDialog(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(), "13-automatic-connection-settings");
    }
    @Test public void failedInvitationKeepsPreviousServiceAndSession() throws Exception {
        String originalId = UUID.randomUUID().toString();
        activity.state.connection.install(profile(originalId, "https://existing.example"));
        activity.state.preferences.edit().putString("codexUrl", "https://existing.example").putString("codexSession", "fixture-session").apply();
        try (LocalLoginServer wrong = new LocalLoginServer()) {
            activity.importConnection(ServiceConnection.code(profile(UUID.randomUUID().toString(), wrong.url())));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); awaitAccount();
            assertTrue(dialog.isShowing()); assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertEquals("https://existing.example", activity.state.serviceUrl()); assertEquals("fixture-session", activity.state.account.token());
            dialog.dismiss();
        }
    }
    @Test public void upgradeDoesNotOverwriteAnotherImportedService() throws Exception {
        org.junit.Assume.assumeTrue(Arrays.asList(activity.getAssets().list("")).contains("connection.json"));
        String id = UUID.randomUUID().toString();
        activity.state.connection.install(profile(id, "https://my-service.example"));
        activity.state.preferences.edit().remove("packagedConnection").apply(); activity.state.connection.installPackagedProfile();
        assertEquals("https://my-service.example", activity.state.serviceUrl());
        assertEquals(id, activity.state.preferences.getString("connectionId", ""));
    }
    @Test public void gatewayFailurePreservesLoginAndSchedulesReconnect() {
        activity.state.preferences.edit().putString("url", "https://test.example").apply();
        activity.state.account.code = "TEST-1234";
        activity.state.account.failed("https://test.example", new StudioState.HttpError(502, "temporary"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("TEST-1234", activity.state.account.code);
        assertTrue(activity.state.account.status.contains("正在重新连接"));
    }
}
