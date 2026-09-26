package com.naifrog.studio;

import android.content.*;
import android.net.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class ServiceConnection {
    final Context context;
    final SharedPreferences prefs;
    volatile String lastRoute = "";
    final Map<String, Long> verified = Collections.synchronizedMap(new HashMap<>());
    ServiceConnection(Context context, SharedPreferences prefs) { this.context = context; this.prefs = prefs; }

    static JSONObject parse(String text) throws Exception {
        text = text.trim();
        if (text.startsWith("https://") || text.startsWith("http://")) text = Uri.parse(text).getFragment();
        if (text == null) throw new IOException("请粘贴完整邀请链接或连接码");
        if (text.startsWith("naifrog://")) text = Uri.parse(text).getQueryParameter("code");
        if (text == null || text.length() > 16000) throw new IOException("请粘贴电脑生成的完整连接码");
        if (text.startsWith("NAIFROG1.")) text = new String(Base64.decode(text.substring(9), Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8);
        JSONObject profile = new JSONObject(text);
        if (profile.optInt("version") != 1 || !profile.optString("serviceId").matches("[a-fA-F0-9-]{36}")) throw new IOException("连接码格式不正确");
        JSONArray endpoints = profile.getJSONArray("endpoints");
        if (endpoints.length() == 0 || endpoints.length() > 4) throw new IOException("连接码中没有有效服务地址");
        for (int i = 0; i < endpoints.length(); i++) {
            URI uri = new URI(endpoints.getString(i));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && StudioState.isLocalServiceHost(uri.getHost())))) throw new IOException("服务地址格式不正确");
            endpoints.put(i, endpoints.getString(i).replaceAll("/+$", ""));
        }
        return profile;
    }
    void installPackagedProfile() {
        try (InputStream in = context.getAssets().open("connection.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); MainActivity.copy(in, bytes);
            String revision = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()), Base64.NO_WRAP);
            if (!revision.equals(prefs.getString("packagedConnection", ""))) {
                JSONObject profile = parse(bytes.toString(StandardCharsets.UTF_8.name()));
                String currentId = prefs.getString("connectionId", "");
                if (currentId.isEmpty() || currentId.equals(profile.getString("serviceId"))) install(profile);
                prefs.edit().putString("packagedConnection", revision).apply();
            }
        } catch (Exception ignored) { }
    }
    synchronized void install(JSONObject profile) throws Exception {
        String id = profile.getString("serviceId"), oldUrl = prefs.getString("url", BuildConfig.SERVICE_URL);
        boolean sameService = id.equals(prefs.getString("connectionId", ""));
        String url = profile.getJSONArray("endpoints").getString(0), token = profile.optString("token", "");
        SharedPreferences.Editor changes = prefs.edit().putString("connectionId", id).putString("connectionEndpoints", profile.getJSONArray("endpoints").toString())
                .putString("url", url).putString("token", token);
        if (sameService) {
            if (oldUrl.equals(prefs.getString("codexUrl", ""))) changes.putString("codexUrl", url);
            if (oldUrl.equals(prefs.getString("jobUrl", ""))) changes.putString("jobUrl", url).putString("jobToken", token);
        } else {
            if (!prefs.getString("job", "").isEmpty()) throw new IOException("请先完成当前作品，再连接另一台服务");
            changes.remove("codexUrl").remove("codexSession").remove("loginRequest");
        }
        changes.apply(); verified.clear(); lastRoute = "";
    }
    void clearProfile() { prefs.edit().remove("connectionId").remove("connectionEndpoints").apply(); verified.clear(); lastRoute = ""; }

    JSONObject checkProfile(JSONObject profile) throws Exception {
        List<String> endpoints = new ArrayList<>();
        JSONArray entries = profile.getJSONArray("endpoints");
        for (int i = 0; i < entries.length(); i++) endpoints.add(entries.getString(i));
        Exception last = null;
        for (Route route : routes(endpoints)) {
            try {
                verify(route, profile.getString("serviceId"));
                JSONObject result = new JSONObject(new String(exchange(route, "/v1/status", "GET", null, profile.optString("token"), "", "", 8000), StandardCharsets.UTF_8));
                if (!result.optBoolean("ready")) throw new IOException("服务已连接，生成方式尚未配置完成，请联系服务提供者");
                return result;
            } catch (StudioState.HttpError e) { if (e.code == 401) throw e; last = e; }
            catch (IOException e) { last = e; }
        }
        throw new IOException("邀请入口暂未连通，请向服务提供者获取最新邀请链接。" + (last == null ? "" : "\n" + last.getMessage()), last);
    }
    static String code(JSONObject profile) {
        return "NAIFROG1." + Base64.encodeToString(profile.toString().getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
    String invitation() throws Exception {
        String primary = prefs.getString("url", BuildConfig.SERVICE_URL);
        if (primary.isEmpty()) throw new IOException("请先连接生成服务");
        JSONObject status = new JSONObject(new String(request(primary + "/v1/status", "GET", null, prefs.getString("token", ""), ""), StandardCharsets.UTF_8));
        JSONArray endpoints = new JSONArray(prefs.getString("connectionEndpoints", "[]"));
        if (endpoints.length() == 0) endpoints.put(primary);
        for (int i = 0; i < endpoints.length(); i++) {
            String endpoint = endpoints.getString(i);
            if (lastRoute.startsWith(endpoint + ":")) { primary = endpoint; break; }
        }
        JSONArray ordered = new JSONArray().put(primary);
        for (int i = 0; i < endpoints.length(); i++) if (!primary.equals(endpoints.getString(i))) ordered.put(endpoints.getString(i));
        JSONObject profile = parse(new JSONObject().put("version", 1).put("serviceId", status.getString("serviceId"))
                .put("endpoints", ordered).put("token", prefs.getString("token", "")).toString());
        if (!primary.startsWith("https://")) throw new IOException("请先使用公网 HTTPS 入口，再邀请其他用户");
        return primary + "/pair#" + code(profile);
    }
    static boolean retryable(Exception e) {
        return e instanceof IOException && (!(e instanceof StudioState.HttpError) || Arrays.asList(502, 503, 504, 520, 522, 523, 524, 530).contains(((StudioState.HttpError)e).code));
    }

    static final class Route {
        final String root, key;
        final Network network;
        final boolean direct;
        Route(String root, boolean direct, Network network) {
            this.root = root; this.direct = direct; this.network = network;
            key = root + ":" + (network == null ? direct ? "direct" : "default" : "network-" + network);
        }
        HttpURLConnection open(String suffix) throws IOException {
            URL url = new URL(root + suffix);
            return (HttpURLConnection)(network != null ? network.openConnection(url, Proxy.NO_PROXY) : direct ? url.openConnection(Proxy.NO_PROXY) : url.openConnection());
        }
    }
    List<Route> routes(List<String> roots) {
        List<Route> routes = new ArrayList<>();
        for (String root : roots) {
            boolean local = StudioState.isLocalServiceHost(Uri.parse(root).getHost());
            routes.add(new Route(root, local, null));
            if (!local) routes.add(new Route(root, true, null));
        }
        try {
            ConnectivityManager manager = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager != null) for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities cap = manager.getNetworkCapabilities(network);
                if (cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        && (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))) {
                    for (String root : roots) routes.add(new Route(root, true, network));
                }
            }
        } catch (SecurityException ignored) { }
        routes.sort(Comparator.comparing(route -> !route.key.equals(lastRoute)));
        return routes;
    }
    byte[] request(String url, String method, byte[] body, String token, String session) throws Exception {
        String primary = prefs.getString("url", BuildConfig.SERVICE_URL), suffix = "", expectedId = "";
        List<String> roots = new ArrayList<>();
        if (!primary.isEmpty() && url.startsWith(primary + "/") && !prefs.getString("connectionId", "").isEmpty()) {
            suffix = url.substring(primary.length()); expectedId = prefs.getString("connectionId", "");
            JSONArray entries = new JSONArray(prefs.getString("connectionEndpoints", "[]"));
            for (int i = 0; i < entries.length(); i++) roots.add(entries.getString(i));
        } else {
            URI target = URI.create(url); String origin = target.getScheme() + "://" + target.getRawAuthority();
            roots.add(origin); suffix = url.substring(origin.length());
        }
        boolean login = method.equals("POST") && suffix.endsWith("/v1/codex/login");
        String requestId = login ? prefs.getString("loginRequest", "") : "";
        if (requestId.isEmpty()) requestId = UUID.randomUUID().toString();
        if (login) prefs.edit().putString("loginRequest", requestId).apply();
        IOException last = null;
        for (Route route : routes(roots)) {
            try {
                if (!expectedId.isEmpty()) verify(route, expectedId);
                byte[] result = exchange(route, suffix, method, body, token, session, requestId, login || body != null || suffix.endsWith("/result") ? 90000 : 15000);
                lastRoute = route.key;
                if (login) prefs.edit().remove("loginRequest").apply();
                return result;
            } catch (StudioState.HttpError e) {
                if (!Arrays.asList(502, 503, 504, 520, 522, 523, 524, 530).contains(e.code)) throw e;
                last = e;
            } catch (IOException e) { last = e; }
        }
        if (last instanceof StudioState.HttpError) throw last;
        throw new IOException("生成服务暂时未连通。请检查网络，或向服务提供者获取最新邀请链接，在“连接服务”中导入。", last);
    }
    void verify(Route route, String id) throws Exception {
        String key = id + ":" + route.key;
        Long at = verified.get(key);
        if (at != null && System.currentTimeMillis() - at < 60000) return;
        JSONObject health;
        try { health = new JSONObject(new String(exchange(route, "/health", "GET", null, "", "", "", 5000), StandardCharsets.UTF_8)); }
        catch (JSONException e) { throw new IOException("此入口没有返回有效的生成服务信息", e); }
        if (!id.equals(health.optString("serviceId"))) throw new IOException("当前地址已不是配对的服务，请导入最新连接码");
        verified.put(key, System.currentTimeMillis());
    }
    static byte[] exchange(Route route, String suffix, String method, byte[] body, String token, String session, String requestId, int readTimeout) throws Exception {
        HttpURLConnection connection = route.open(suffix);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method); connection.setConnectTimeout(6000); connection.setReadTimeout(readTimeout);
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (!session.isEmpty()) connection.setRequestProperty("X-Codex-Session", session);
            if (!requestId.isEmpty()) connection.setRequestProperty("X-Request-Id", requestId);
            if (body != null) { connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(body.length); try (OutputStream out = connection.getOutputStream()) { out.write(body); } }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            byte[] data; try (InputStream in = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (in != null) MainActivity.copy(in, bytes); data = bytes.toByteArray();
            }
            if (status < 200 || status >= 300) {
                String message = "服务响应异常（" + status + "）";
                try { message = new JSONObject(new String(data, StandardCharsets.UTF_8)).optString("error", message); } catch (JSONException ignored) { }
                throw new StudioState.HttpError(status, message);
            }
            return data;
        } finally { connection.disconnect(); }
    }
}
