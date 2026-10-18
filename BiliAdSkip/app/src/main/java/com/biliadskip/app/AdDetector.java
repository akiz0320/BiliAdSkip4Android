package com.biliadskip.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 广告检测：云端缓存优先（仅加速）→ 字幕 / 音频转写 → LLM 分析 → 秒区间
 * prompt 与解析逻辑对齐 VideoAdGuard 的 content.ts
 */
public final class AdDetector {

    public static final String SYSTEM_PROMPT =
            "你是一个专业的视频内容分析师，专门识别视频中的植入广告。"
                    + "只有当内容明确包含商业推广、产品推荐且有明确的购买引导时，才认定为广告。";

    /** 检测结果 */
    public static final class Result {
        public boolean exist;
        /** 本次检测因网络/接口异常而中断（与"确实没有广告"区分开，调用方据此决定是否重试） */
        public boolean failed;
        public final List<String> goodNames = new ArrayList<>();
        /** 每段广告的秒区间 [start, end] */
        public final List<double[]> ranges = new ArrayList<>();
        public String source = "";
        public String note = "";
    }

    private AdDetector() {
    }

    /** 阻塞式检测，必须在后台线程调用 */
    public static Result detect(Context ctx, String bvid, long cid, long aid, String title) {
        Result r = new Result();
        String cookie = Prefs.cookie(ctx);

        // ---------- 1. 云端缓存（仅当有非空区间时采用） ----------
        if (Prefs.useCloudCache(ctx)) {
            try {
                JSONObject data = BiliApi.cloudCache(Prefs.workerUrl(ctx), bvid);
                JSONArray ranges = data == null ? null : data.optJSONArray("adTimeRanges");
                if (ranges != null && ranges.length() > 0) {
                    for (int i = 0; i < ranges.length(); i++) {
                        JSONArray seg = ranges.optJSONArray(i);
                        if (seg != null && seg.length() == 2) {
                            r.ranges.add(new double[]{seg.optDouble(0), seg.optDouble(1)});
                        }
                    }
                    r.exist = true;
                    r.source = "cloud";
                    r.note = "云端缓存命中（" + data.optString("model", "-") + "）";
                    Logx.i("[检测] 云端缓存命中，" + r.ranges.size() + " 段广告");
                    return r;
                }
                Logx.i("[检测] 云端无有效区间（命中但为空），继续本地检测");
            } catch (Exception e) {
                Logx.i("[检测] 云端缓存不可用：" + e.getMessage());
            }
        }

        // ---------- 2. 素材：置顶评论 + 商品链接 ----------
        String topComment = null;
        JSONObject goodsInfo = new JSONObject();
        try {
            JSONObject reply = BiliApi.replyTop(aid, cookie);
            JSONArray tops = reply.optJSONArray("top_replies");
            if (tops != null && tops.length() > 0) {
                JSONObject content = tops.getJSONObject(0).optJSONObject("content");
                if (content != null) {
                    topComment = content.optString("message", null);
                    JSONObject jump = content.optJSONObject("jump_url");
                    if (jump != null) {
                        java.util.Iterator<String> it = jump.keys();
                        while (it.hasNext()) {
                            String key = it.next();
                            JSONObject v = jump.optJSONObject(key);
                            if (v == null) {
                                continue;
                            }
                            JSONObject item = new JSONObject();
                            item.put("是否为商品链接", v.optString("title", "").length() > 0
                                    || v.optString("pc_url", "").length() > 0);
                            if (v.optString("title", "").length() > 0) {
                                item.put("链接标题", v.optString("title"));
                            }
                            goodsInfo.put(key, item);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logx.i("[检测] 置顶评论获取失败（可忽略）：" + e.getMessage());
        }

        // ---------- 3. 字幕：优先官方字幕，其次音频转写 ----------
        JSONArray captionBody = null;
        String subtitleSource = null;
        try {
            JSONObject player = BiliApi.playerV2(bvid, cid, cookie);
            JSONObject subtitle = player.optJSONObject("subtitle");
            JSONArray subs = subtitle == null ? null : subtitle.optJSONArray("subtitles");
            if (subs != null && subs.length() > 0) {
                String url = subs.getJSONObject(0).optString("subtitle_url", "");
                if (url.startsWith("//")) {
                    url = "https:" + url;
                }
                Logx.i("[检测] 下载官方字幕：" + url.split("\\?")[0]);
                JSONArray body = BiliApi.captions(url, cookie);
                if (body != null && body.length() > 0) {
                    captionBody = body;
                    subtitleSource = "官方字幕(" + subs.getJSONObject(0).optString("lan", "?") + ")";
                }
            } else if (!Prefs.isLoggedIn(ctx)) {
                r.note = "未登录 → 拿不到官方字幕（字幕接口需要登录态）";
                Logx.i("[检测] " + r.note);
            }
        } catch (Exception e) {
            r.failed = true;
            Logx.i("[检测] 字幕获取失败：" + e);
        }

        if (captionBody == null) {
            String groqKey = Prefs.groqKey(ctx);
            if (groqKey.isEmpty()) {
                if (r.note.isEmpty()) {
                    r.note = "无字幕，且未配置 Groq 密钥，无法检测";
                    Logx.i("[检测] " + r.note);
                }
                return r;
            }
            try {
                captionBody = transcribeAudio(ctx, bvid, cid, cookie, groqKey, aid);
                if (captionBody != null) {
                    subtitleSource = "Groq 音频转写";
                }
            } catch (Exception e) {
                r.failed = true;
                r.note = "音频转写失败：" + e.getMessage();
                Logx.i("[检测] " + r.note);
                return r;
            }
        }
        if (captionBody == null || captionBody.length() == 0) {
            r.failed = true;
            r.note = "拿不到可用字幕，无法检测";
            return r;
        }

        // ---------- 4. LLM ----------
        if (!Prefs.llmReady(ctx)) {
            r.note = "已取得字幕（" + subtitleSource + "），但未配置大模型，无法分析";
            Logx.i("[检测] " + r.note);
            return r;
        }
        try {
            JSONObject captions = new JSONObject();
            for (int i = 0; i < captionBody.length(); i++) {
                captions.put(String.valueOf(i),
                        captionBody.getJSONObject(i).optString("content", ""));
            }
            String prompt = buildPrompt(title, topComment, goodsInfo, captions);
            Logx.i("[检测] 调用大模型分析（" + subtitleSource + "，" + captionBody.length() + " 条字幕）");
            String raw = BiliApi.llm(Prefs.llmBase(ctx), Prefs.llmKey(ctx), Prefs.llmModel(ctx),
                    SYSTEM_PROMPT, prompt);
            parseInto(raw, captionBody.length(), captionBody, r);
            r.source = "llm";
            r.note = "本地大模型检测完成（" + subtitleSource + "）";
            Logx.i("[检测] " + r.note + "，命中 " + r.ranges.size() + " 段");
        } catch (Exception e) {
            r.failed = true;
            r.note = "大模型分析失败：" + e.getMessage();
            Logx.i("[检测] " + r.note);
        }
        return r;
    }

    // ------------------------------------------------------------------ 音频转写

    private static JSONArray transcribeAudio(Context ctx, String bvid, long cid,
                                             String cookie, String groqKey, long aid) throws Exception {
        JSONObject play = BiliApi.playUrl(bvid, cid, cookie);
        JSONObject dash = play.optJSONObject("dash");
        JSONArray audios = dash == null ? null : dash.optJSONArray("audio");
        if (audios == null || audios.length() == 0) {
            throw new IllegalStateException("没有可用的音频流");
        }
        JSONObject best = audios.getJSONObject(0);
        for (int i = 1; i < audios.length(); i++) {
            if (audios.getJSONObject(i).optInt("bandwidth") < best.optInt("bandwidth")) {
                best = audios.getJSONObject(i);
            }
        }
        String baseUrl = best.optString("baseUrl", "");
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("音频流地址为空");
        }
        Logx.i("[检测] 无字幕，开始下载音频转写…");
        byte[] audio = BiliApi.downloadBinary(baseUrl, cookie);
        if (audio.length > 19L * 1024 * 1024) {
            throw new IllegalStateException("音频过大（" + (audio.length / 1048576) + "MB），超过 Groq 限制");
        }
        JSONObject json = BiliApi.groqTranscribe(audio, "audio.m4a", groqKey);
        JSONArray segments = json.optJSONArray("segments");
        if (segments == null || segments.length() == 0) {
            throw new IllegalStateException("转写未返回 segments");
        }
        JSONArray body = new JSONArray();
        String lastText = null;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.getJSONObject(i);
            String text = seg.optString("text", "").trim();
            if (text.isEmpty() || text.equals(lastText)) {
                continue;
            }
            lastText = text;
            body.put(new JSONObject()
                    .put("content", text)
                    .put("from", seg.optDouble("start", 0))
                    .put("to", seg.optDouble("end", 0)));
        }
        Logx.i("[检测] 音频转写完成，" + body.length() + " 条");
        return body;
    }

    // ------------------------------------------------------------------ prompt

    private static String buildPrompt(String title, String topComment,
                                      JSONObject additional, JSONObject captions) {
        return "你需要分析视频内容，识别其中的植入广告。\n\n"
                + "视频信息：\n"
                + "标题：" + title + "\n"
                + "置顶评论：" + (topComment == null || topComment.isEmpty() ? "无" : topComment) + "\n"
                + "附加信息：" + additional + "\n\n"
                + "检测规则：\n"
                + "1. 只有当内容明确包含商业推广、产品推荐且有明确的购买引导时，才认定为广告\n"
                + "2. 纯粹的产品介绍、评测、对比，如果没有明确的购买引导，不认定为广告\n"
                + "3. 如果置顶评论中没有商品链接，则更倾向于认定为无广告\n"
                + "4. 广告内容必须与置顶评论或附加信息中的商品链接标题相对应\n"
                + "5. 一段广告的检测需要完整：包含商品介绍前的转折性引入，介绍商品的特点、优势、适用场景、价格、购买渠道等信息，以及商品介绍后的引导购买部分\n\n"
                + "字幕内容：" + captions + "\n\n"
                + "请严格按照检测规则进行判断，以json格式输出（无注释）：\n"
                + "{\n"
                + "  \"exist\": <bool. true表示存在广告，false表示不存在广告>,\n"
                + "  \"good_name\": <list[string]. 广告的商品名称>,\n"
                + "  \"index_lists\": <list[list[int]]. 二维数组，每一行是[start, end]，表示一段*完整*广告的开头结尾字幕index，包含商品介绍前的引入和结尾后的推荐性、煽动购买的语言>\n"
                + "}";
    }

    // ------------------------------------------------------------------ 解析

    private static String clean(String raw) {
        String noFence = java.util.regex.Pattern.compile("(?s)```.*?```")
                .matcher(raw)
                .replaceAll(m -> m.group().replace("```", ""));
        return noFence
                .replaceAll("(?s)^.*?(\\{[\\s\\S]*\\}).*?$", "$1")
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u201E', '"').replace('\u201F', '"')
                .replace('\u2033', '"')
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u2032', '\'');
    }

    private static JSONObject tryParse(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception ignored) {
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)\\{[\\s\\S]*\\}").matcher(text);
        if (m.find()) {
            try {
                return new JSONObject(m.group()
                        .replace('\u201C', '"').replace('\u201D', '"')
                        .replace('\u2018', '\'').replace('\u2019', '\''));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void parseInto(String raw, int captionCount, JSONArray captionBody, Result r) {
        JSONObject parsed = tryParse(clean(raw));
        if (parsed == null) {
            throw new IllegalStateException("模型返回的 JSON 无法解析");
        }
        boolean exist = parsed.optBoolean("exist", false);
        JSONArray names = parsed.optJSONArray("good_name");
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String n = names.optString(i, "");
                if (!n.isEmpty()) {
                    r.goodNames.add(n);
                }
            }
        }
        JSONArray lists = parsed.optJSONArray("index_lists");
        List<int[]> ranges = new ArrayList<>();
        if (lists != null) {
            for (int i = 0; i < lists.length(); i++) {
                JSONArray item = lists.optJSONArray(i);
                if (item == null || item.length() != 2) {
                    continue;
                }
                int s = item.optInt(0, -1);
                int e = item.optInt(1, -1);
                if (s < 0 || e < 0) {
                    continue;
                }
                if (captionCount > 0) {
                    s = Math.min(s, captionCount - 1);
                    e = Math.min(e, captionCount - 1);
                }
                if (s > e) {
                    int t = s;
                    s = e;
                    e = t;
                }
                ranges.add(new int[]{s, e});
            }
        }
        Collections.sort(ranges, Comparator.comparingInt(a -> a[0]));

        List<int[]> merged = new ArrayList<>();
        for (int[] cur : ranges) {
            if (merged.isEmpty() || cur[0] > merged.get(merged.size() - 1)[1] + 1) {
                merged.add(cur);
            } else {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], cur[1]);
            }
        }

        r.exist = exist && !merged.isEmpty();
        for (int[] seg : merged) {
            double from = captionBody.optJSONObject(seg[0]) == null
                    ? 0 : captionBody.optJSONObject(seg[0]).optDouble("from", 0);
            double to = captionBody.optJSONObject(seg[1]) == null
                    ? 0 : captionBody.optJSONObject(seg[1]).optDouble("to", 0);
            r.ranges.add(new double[]{from, to});
        }
    }
}
