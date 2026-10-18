// B 站 Web API 客户端：对齐 content.js 里的 BilibiliService
// 关键验证点：不带登录 Cookie（BILI_COOKIE）时，这些接口还能拿到多少数据
import { encWbi } from './wbi.js';

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const REFERER = 'https://www.bilibili.com/';

function baseHeaders() {
  const headers = { 'User-Agent': UA, Referer: REFERER };
  if (process.env.BILI_COOKIE) headers.Cookie = process.env.BILI_COOKIE;
  return headers;
}

async function call(url, params = {}, { signed = false } = {}) {
  const finalParams = signed ? await encWbi(params) : params;
  const qs = new URLSearchParams(finalParams).toString();
  const full = `${url}?${qs}`;
  const res = await fetch(full, { headers: baseHeaders() });
  if (!res.ok) throw new Error(`HTTP ${res.status} @ ${full}`);
  const json = await res.json();
  if (json.code !== 0) throw new Error(`[code=${json.code}] ${json.message} @ ${full}`);
  return json.data;
}

/** 视频基础信息（含 aid / cid / owner.mid / title） */
export const getVideoInfo = (bvid) => call('https://api.bilibili.com/x/web-interface/view', { bvid });

/**
 * 评论区：x/v2/reply/wbi/main（type=1 表示视频稿件，oid 取 aid）
 * 注意：旧的 x/v2/reply 已对匿名请求返回空数据，必须用这个带 WBI 签名的新接口。
 * mode=3 热度排序 / mode=2 时间排序
 */
export const getComments = (oid, { type = 1, mode = 3, next = 0, ps = 20 } = {}) =>
  call(
    'https://api.bilibili.com/x/v2/reply/wbi/main',
    { oid, type, mode, next, ps, plat: 1, web_location: 1315875 },
    { signed: true },
  );

/** 播放器信息：这里能拿到官方字幕列表（需要 WBI 签名） */
export const getPlayerInfo = (bvid, cid) =>
  call('https://api.bilibili.com/x/player/wbi/v2', { bvid, cid }, { signed: true });

/** 播放地址：dash.audio[] 用于无字幕时的音频转写 */
export const getPlayUrl = (bvid, cid) =>
  call('https://api.bilibili.com/x/player/wbi/playurl', { bvid, cid, fnval: 16 }, { signed: true });

/** 字幕 JSON：{ body: [{ content, from, to }] } */
export async function getCaptions(url) {
  const res = await fetch(url, { headers: baseHeaders() });
  if (!res.ok) throw new Error(`字幕下载失败 HTTP ${res.status}`);
  return res.json();
}

/** 取置顶评论的 message 与 jump_url（jump_url 是服务端的“超链转义”信息，不含跳转动作） */
export function pickTopComment(replyData) {
  const top = replyData?.top_replies?.[0]?.content ?? null;
  if (!top) return { message: null, jumpUrl: null };
  return { message: top.message ?? null, jumpUrl: top.jump_url ?? null };
}

/**
 * 把置顶评论里的商品链接信息整理成 prompt 用的“附加信息”
 * 实测 jump_url 形如：{ "https://b23.tv/xxx": { title, app_url_schema, prefix_icon, ... } }
 * 判定商品链接以 title 为主（原插件的 goods_item_id/pc_url 判据在新接口里不稳定）
 */
export function pickGoodsInfo(jumpUrl) {
  const info = {};
  if (!jumpUrl || typeof jumpUrl !== 'object') return info;
  for (const [key, value] of Object.entries(jumpUrl)) {
    if (!value || typeof value !== 'object') continue;
    if (value?.extra?.is_word_search === true) continue;
    const isGoods = Boolean(value.title || value.extra?.goods_item_id || value.pc_url);
    const item = { 是否为商品链接: isGoods };
    if (value.title) item.链接标题 = value.title;
    if (value.app_name) item.平台名称 = value.app_name;
    info[key] = item;
  }
  return info;
}
