package com.naifrog.studio;

import org.json.JSONObject;

final class CodexAccount {
    final StudioState state;
    boolean busy, loggedIn;
    String email = "", plan = "", code = "", verificationUrl = "", error = "", status = "登录后使用你的 Codex 账号生成";
    private final Runnable poll = () -> refresh(true);
    private int reconnects;

    CodexAccount(StudioState state) { this.state = state; }
    String token() {
        return state.serviceUrl().equals(state.preferences.getString("codexUrl", ""))
                ? state.preferences.getString("codexSession", "") : "";
    }
    void changedService() {
        loggedIn = false; email = ""; plan = ""; code = ""; verificationUrl = ""; error = "";
        status = "登录后使用你的 Codex 账号生成";
        state.main.removeCallbacks(poll);
        if (!token().isEmpty()) refresh(true);
    }
    void startLogin() {
        if (busy) return;
        busy = true; error = ""; status = "正在连接生成服务…"; state.notifyUi();
        final String service = state.serviceUrl(), session = token(), connection = state.preferences.getString("token", "");
        state.control.execute(() -> {
            boolean connected = false;
            try {
                JSONObject health = StudioState.jsonRequest(service + "/v1/status", "GET", null, connection);
                JSONObject providers = health.optJSONObject("providers");
                if (providers == null || !providers.optBoolean("codex")) throw new java.io.IOException("已连接生成服务，请在电脑端启用 Codex 登录。");
                connected = true;
                state.main.post(() -> {
                    if (!service.equals(state.serviceUrl())) return;
                    status = "生成服务已连接，正在获取官方登录验证码…"; state.notifyUi();
                });
                JSONObject response = StudioState.jsonRequest(service + "/v1/codex/login", "POST", new byte[0], connection, session);
                state.main.post(() -> {
                    busy = false;
                    if (!service.equals(state.serviceUrl())) return;
                    state.preferences.edit().putString("codexUrl", service).putString("codexSession", response.optString("sessionToken")).apply();
                    code = response.optString("userCode"); verificationUrl = response.optString("verificationUrl");
                    status = "复制验证码，前往官方页面完成登录"; state.notifyUi(); schedule();
                });
            } catch (Exception e) { failed(service, e, connected ? "获取 Codex 验证码失败：" : ""); }
        });
    }
    void refresh(boolean quiet) {
        if (busy || token().isEmpty() || state.serviceUrl().isEmpty()) return;
        busy = true;
        if (!quiet) { error = ""; status = "正在检查登录状态…"; state.notifyUi(); }
        final String service = state.serviceUrl(), session = token(), connection = state.preferences.getString("token", "");
        state.control.execute(() -> {
            try {
                JSONObject response = StudioState.jsonRequest(service + "/v1/codex/account", "GET", null, connection, session);
                state.main.post(() -> {
                    busy = false;
                    if (!service.equals(state.serviceUrl())) return;
                    apply(response); state.notifyUi();
                    if (!loggedIn && !code.isEmpty()) schedule();
                });
            } catch (Exception e) { failed(service, e); }
        });
    }
    void apply(JSONObject response) {
        error = ""; reconnects = 0; loggedIn = response.optBoolean("authenticated");
        JSONObject account = response.optJSONObject("account"), login = response.optJSONObject("login");
        if (loggedIn && account != null) {
            email = account.isNull("email") ? "Codex 账号" : account.optString("email", "Codex 账号");
            plan = account.optString("planType", ""); code = ""; verificationUrl = "";
            status = "已登录，可以开始创作"; state.main.removeCallbacks(poll);
        } else {
            email = ""; plan = "";
            String loginState = login == null ? "idle" : login.optString("state", "idle");
            if (loginState.equals("pending")) {
                code = login.optString("userCode"); verificationUrl = login.optString("verificationUrl");
                status = "等待官方登录完成，返回 App 后自动更新";
            } else {
                code = ""; verificationUrl = ""; status = "登录后使用你的 Codex 账号生成";
                if (loginState.equals("failed")) error = login.optString("error", "本次登录未完成，请重新登录");
            }
        }
    }
    void logout() {
        if (busy || state.busy || state.hasPending()) return;
        busy = true; error = ""; state.main.removeCallbacks(poll); state.notifyUi();
        final String service = state.serviceUrl(), session = token(), connection = state.preferences.getString("token", "");
        state.control.execute(() -> {
            try {
                JSONObject response = StudioState.jsonRequest(service + "/v1/codex/logout", "POST", new byte[0], connection, session);
                state.main.post(() -> { busy = false; if (!service.equals(state.serviceUrl())) return; apply(response); status = "已退出 Codex 账号"; state.notifyUi(); });
            } catch (Exception e) { failed(service, e); }
        });
    }
    void failed(String service, Exception e) {
        failed(service, e, "");
    }
    void failed(String service, Exception e, String prefix) {
        state.main.post(() -> {
            busy = false; if (!service.equals(state.serviceUrl())) return;
            error = prefix + e.getMessage(); status = "连接未完成，可检查服务地址后重试";
            if (e instanceof StudioState.HttpError && ((StudioState.HttpError)e).code == 401 && !"连接码不正确".equals(e.getMessage())) {
                loggedIn = false; code = "";
                state.preferences.edit().remove("codexSession").apply();
            }
            if (ServiceConnection.retryable(e) && !code.isEmpty() && reconnects++ < 5) {
                status = "正在重新连接，登录验证码继续保留";
                state.main.removeCallbacks(poll); state.main.postDelayed(poll, Math.min(15000, 2500L * reconnects));
            }
            state.notifyUi();
        });
    }
    void schedule() { state.main.removeCallbacks(poll); state.main.postDelayed(poll, 2500); }
}
