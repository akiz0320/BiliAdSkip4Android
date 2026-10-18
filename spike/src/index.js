// 接口验证原型 CLI：按 VideoAdGuard 的真实链路逐段跑，打印每一段的成败
// 用法：
//   node src/index.js BV1UGeBztE2T
//   node src/index.js BV1UGeBztE2T --no-llm
// 可选环境变量：
//   BILI_COOKIE      例如 "SESSDATA=xxx;buvid3=xxx"（不设置即匿名验证）
//   VAG_LLM_BASE_URL / VAG_LLM_API_KEY / VAG_LLM_MODEL   （OpenAI 兼容接口）
//   VAG_WORKER_URL   云端缓存 Worker 地址，默认 http://videoadguard-api.0100320.xyz
import {
  getVideoInfo,
  getComments,
  getPlayerInfo,
  getCaptions,
  pickTopComment,
  pickGoodsInfo,
} from './bilibili.js';
import { getWbiKeys } from './wbi.js';
import { fetchRemoteCache } from './cache.js';
import {
  SYSTEM_PROMPT,
  buildPrompt,
  parseResult,
  index2second,
  second2time,
  toCaptionsMap,
} from './detect.js';
import { llmConfigured, invokeLLM, llmConfig } from './llm.js';

const step = (n, title) => console.log(`\n[${n}] ${title}`);
const ok = (s, d = '') => console.log(`  ✓ ${s}${d ? `  ${d}` : ''}`);
const bad = (s, d = '') => console.log(`  ✗ ${s}${d ? `  ${d}` : ''}`);
const note = (s) => console.log(`  · ${s}`);

const argv = process.argv.slice(2);
const noLlm = argv.includes('--no-llm');
const bvid = argv.find((a) => /^BV/i.test(a)) || 'BV1UGeBztE2T';

const checkpoints = {};
console.log('='.repeat(72));
console.log('VideoAdGuard 检测链路 · 接口验证原型');
console.log(`目标视频：${bvid}`);
console.log(`登录态：${process.env.BILI_COOKIE ? '已提供 BILI_COOKIE' : '匿名（未提供 Cookie）'}`);
console.log('='.repeat(72));

// ---- 1. WBI ----
step(1, 'WBI 签名前置：x/web-interface/nav 取 img_key / sub_key');
try {
  const [imgKey, subKey] = await getWbiKeys();
  ok('nav 接口可用', `img_key=${imgKey.slice(0, 10)}… sub_key=${subKey.slice(0, 10)}…`);
  checkpoints.WBI = true;
} catch (e) {
  bad('nav 接口失败', e.message);
  checkpoints.WBI = false;
}

// ---- 2. 视频基础信息 ----
step(2, '视频基础信息：x/web-interface/view');
let video = null;
try {
  video = await getVideoInfo(bvid);
  ok('拿到稿件信息', `aid=${video.aid} cid=${video.cid} 时长=${video.duration}s 分P=${video.videos}`);
  note(`标题：${video.title}`);
  note(`UP：${video.owner?.name} (mid=${video.owner?.mid})`);
  checkpoints.view = true;
} catch (e) {
  bad('view 失败', e.message);
  checkpoints.view = false;
}

// ---- 3. 云端缓存 ----
step(3, '云端缓存：GET /api/cache/{bvid}（验证社区结果能否复用）');
try {
  const cache = await fetchRemoteCache(bvid);
  if (cache.status === 'hit') {
    const d = cache.data;
    ok('命中云端缓存', `广告段=${d.adTimeRanges.length} 来源=${d.source} 准确度=${d.accuracy} 模型=${d.model || '-'}`);
    for (const [start, end] of d.adTimeRanges) {
      note(`云端区间 ${second2time(start)} → ${second2time(end)}（${(end - start).toFixed(1)}s）`);
    }
    checkpoints.cloudCache = true;
  } else if (cache.status === 'miss') {
    note('未命中：该视频云端暂无记录（这是正常情况，不代表链路有问题）');
    checkpoints.cloudCache = true;
  } else {
    bad('云端查询失败', cache.reason);
    checkpoints.cloudCache = false;
  }
} catch (e) {
  bad('云端查询异常', e.message);
  checkpoints.cloudCache = false;
}

// ---- 4. 置顶评论 ----
step(4, '置顶评论：x/v2/reply/wbi/main（口播广告判定的关键上下文）');
let topComment = null;
let goodsInfo = {};
try {
  const data = await getComments(video.aid);
  const top = pickTopComment(data);
  topComment = top.message;
  goodsInfo = pickGoodsInfo(top.jumpUrl);
  if (topComment) ok('拿到置顶评论', `${topComment.slice(0, 50)}${topComment.length > 50 ? '…' : ''}`);
  else note('该视频没有置顶评论');
  const goodsKeys = Object.keys(goodsInfo);
  if (goodsKeys.length) ok('识别到商品链接信息', JSON.stringify(goodsInfo).slice(0, 200));
  else note('置顶评论中未发现商品链接（按规则倾向判为无广告）');
  checkpoints.topComment = true;
} catch (e) {
  bad('reply 失败', e.message);
  checkpoints.topComment = false;
}

// ---- 5. 官方字幕 ----
step(5, '官方字幕：x/player/wbi/v2（需要 WBI 签名）');
let captionBody = [];
try {
  const player = await getPlayerInfo(bvid, video.cid);
  const subs = player?.subtitle?.subtitles ?? [];
  if (!subs.length) {
    bad('该视频没有官方字幕', '→ 无字幕时插件会退化为 Groq 音频转写');
    checkpoints.subtitle = false;
  } else {
    ok('字幕列表可用', `${subs.length} 条，首选 lan=${subs[0].lan}`);
    const cap = await getCaptions('https:' + subs[0].subtitle_url);
    captionBody = Array.isArray(cap?.body) ? cap.body : [];
    ok('字幕内容已下载', `共 ${captionBody.length} 条，首条 ${captionBody[0]?.from}s → 末条 ${captionBody.at(-1)?.to}s`);
    checkpoints.subtitle = captionBody.length > 0;
  }
} catch (e) {
  bad('player/wbi/v2 失败', e.message);
  checkpoints.subtitle = false;
}

// ---- 6. LLM 检测 ----
step(6, 'LLM 检测：prompt → 解析 → 秒区间');
let ranges = [];
if (!captionBody.length) {
  note('无字幕，跳过 LLM 检测');
  checkpoints.llm = false;
} else if (noLlm || !llmConfigured()) {
  note('已跳过（--no-llm 或未配置 VAG_LLM_* 环境变量）');
  checkpoints.llm = false;
} else {
  try {
    const cfg = llmConfig();
    const prompt = buildPrompt({
      title: video.title,
      topComment,
      additionalMessages: goodsInfo,
      captions: toCaptionsMap(captionBody),
    });
    note(`prompt 长度 ${prompt.length} 字符，模型 ${cfg.model}`);
    const raw = await invokeLLM(SYSTEM_PROMPT, prompt);
    const result = parseResult(raw, captionBody.length);
    ranges = index2second(result.index_lists, captionBody);
    ok('模型返回已解析', `exist=${result.exist}`);
    if (result.good_name.length) note(`商品名称：${result.good_name.join('、')}`);
    if (!ranges.length) note('未检测到广告区间');
    for (const [start, end] of ranges) {
      note(`广告段 ${second2time(start)} → ${second2time(end)}（${(end - start).toFixed(1)}s）`);
    }
    checkpoints.llm = true;
  } catch (e) {
    bad('LLM 检测失败', e.message);
    checkpoints.llm = false;
  }
}

// ---- 汇总 ----
console.log('\n' + '='.repeat(72));
console.log('验证汇总');
console.log('='.repeat(72));
const labels = {
  WBI: 'WBI 签名可用',
  view: '视频信息（免登录）',
  cloudCache: '云端缓存协议',
  topComment: '置顶评论（免登录）',
  subtitle: '官方字幕（免登录）',
  llm: 'LLM 端到端检测',
};
for (const [key, label] of Object.entries(labels)) {
  const state = checkpoints[key];
  const mark = state === true ? '通过' : state === false ? '未通过' : '未执行';
  console.log(`  ${state === true ? '✓' : state === false ? '✗' : '-'} ${label.padEnd(20, ' ')} ${mark}`);
}
if (ranges.length) {
  console.log(`\n  广告跳过区间：${ranges.map(([a, b]) => `${second2time(a)}~${second2time(b)}`).join(' | ')}`);
}
console.log('');
