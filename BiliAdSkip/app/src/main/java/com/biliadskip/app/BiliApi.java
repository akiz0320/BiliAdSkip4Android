package com.biliadskip.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** B站 + LLM + Groq 的接口客户端（无第三方依赖，只用 HttpURLConnection + org.json） */
public final class BiliApi {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final String CLIENT_VERSION = "1.4.1";

    private BiliApi() {
    }

    // ------------------------------------------------------------------ 基础 HTTP

    private static HttpURLConnection open(String url, String method, String cookie, int readTimeoutMs)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", REFERER);
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(readTimeoutMs);
        return conn;
    }

    private static String bodyOf(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = in == null ? "" : Wbi.readAll(in);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + body);
        }
        return body;
    }

    private static JSONObject apiGet(String url, Map<String, String> params, boolean signed, String cookie)
            throws Exception {
        String qs = signed ? Wbi.toQuery(params, cookie) : plainQuery(params);
        String full = url + "?" + qs;
        JSONObject json = new JSONObject(bodyOf(open(full, "GET", cookie, 15000)));
        int code = json.optInt("code", -1);
        if (code != 0) {
            throw new IllegalStateException("[" + code + "] " + json.optString("message") + " @ " + url);
        }
        return json.getJSONObject("data");
    }

    private static String plainQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ B站接口

    /** 视频信息（含 aid/cid/owner/title/duration） */
    public static JSONObject view(String bvid, String cookie) throws Exception {
        return apiGet("https://api.bilibili.com/x/web-interface/view", p("bvid", bvid), false, cookie);
    }

    public static JSONObject viewByAid(long aid, String cookie) throws Exception {
        return apiGet("https://api.bilibili.com/x/web-interface/view",
                p("aid", String.valueOf(aid)), false, cookie);
    }

    /** 校验登录态：data.isLogin / data.mid */
    public static JSONObject nav(String cookie) throws Exception {
        return apiGet("https://api.bilibili.com/x/web-interface/nav",
                new LinkedHashMap<>(), false, cookie);
    }

    /** 播放器信息（含官方字幕列表），需要 WBI */
    public static JSONObject playerV2(String bvid, long cid, String cookie) throws Exception {
        return apiGet("https://api.bilibili.com/x/player/wbi/v2",
                p("bvid", bvid, "cid", String.valueOf(cid)), true, cookie);
    }

    /** 字幕 JSON：{"body":[{content,from,to}...]} */
    public static JSONArray captions(String subtitleUrl, String cookie) throws Exception {
        JSONObject json = new JSONObject(bodyOf(open(subtitleUrl, "GET", cookie, 20000)));
        return json.optJSONArray("body");
    }

    /** 评论区（新接口，匿名可用）：拿置顶评论与商品链接信息 */
    public static JSONObject replyTop(long aid, String cookie) throws Exception {
        return apiGet("https://api.bilibili.com/x/v2/reply/wbi/main",
                p("oid", String.valueOf(aid), "type", "1", "mode", "3",
                        "next", "0", "ps", "20", "plat", "1", "web_location", "1315875"),
                true, cookie);
    }

    /** 播放地址（用于无字幕时取音频流） */
    public static JSONObject playUrl(String bvid, long cid, String cookie) throws Exception {
        return apiGet("https://api.bilibili.com/x/player/wbi/playurl",
                p("bvid", bvid, "cid", String.valueOf(cid), "fnval", "16"), true, cookie);
    }

    /** 下载音频流 */
    public static byte[] downloadBinary(String url, String cookie) throws Exception {
        HttpURLConnection conn = open(url, "GET", cookie, 120000);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try (InputStream in = conn.getInputStream()) {
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ 云端缓存

    public static JSONObject cloudCache(String workerUrl, String bvid) throws Exception {
        String url = workerUrl.replaceAll("/$", "") + "/api/cache/" + bvid;
        HttpURLConnection conn = open(url, "GET", "", 5000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-Client-Version", CLIENT_VERSION);
        JSONObject json = new JSONObject(bodyOf(conn));
        if (!json.optBoolean("success", false)) {
            throw new IllegalStateException(json.optString("error", "success=false"));
        }
        return json.optJSONObject("data");
    }

    // ------------------------------------------------------------------ LLM

    public static String llm(String baseUrl, String apiKey, String model,
                             String systemPrompt, String userPrompt) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
        payload.put("messages", messages);
        payload.put("max_tokens", 8192);
        payload.put("temperature", 0);

        String url = baseUrl.replaceAll("/$", "") + "/chat/completions";
        HttpURLConnection conn = open(url, "POST", "", 120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        JSONObject json = new JSONObject(bodyOf(conn));
        String text = json.optJSONArray("choices") == null ? null
                : json.getJSONArray("choices").optJSONObject(0) == null ? null
                : json.getJSONArray("choices").getJSONObject(0).optJSONObject("message") == null ? null
                : json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", null);
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalStateException("LLM 未返回文本内容");
        }
        return text.trim();
    }

    // ------------------------------------------------------------------ Groq 语音识别

    public static JSONObject groqTranscribe(byte[] audio, String fileName, String apiKey) throws Exception {
        String boundary = "----BiliAdSkip" + System.currentTimeMillis();
        HttpURLConnection conn = open(
                "https://api.groq.com/openai/v1/audio/transcriptions", "POST", "", 180000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.write("Content-Type: audio/m4a\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            os.write(audio);
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));

            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.write("whisper-large-v3-turbo".getBytes(StandardCharsets.UTF_8));
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));

            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.write("verbose_json".getBytes(StandardCharsets.UTF_8));
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));

            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        return new JSONObject(bodyOf(conn));
    }
}
