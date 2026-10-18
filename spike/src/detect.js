// 检测逻辑：prompt 构建、LLM 结果解析、字幕 index → 秒区间换算
// prompt 与解析均对齐 content.js 的实现

export const SYSTEM_PROMPT =
  '你是一个专业的视频内容分析师，专门识别视频中的植入广告。只有当内容明确包含商业推广、产品推荐且有明确的购买引导时，才认定为广告。';

export function buildPrompt({ title, topComment, additionalMessages, captions }) {
  return `你需要分析视频内容，识别其中的植入广告。

视频信息：
标题：${title}
置顶评论：${topComment || '无'}
附加信息：${JSON.stringify(additionalMessages) || '无'}

检测规则：
1. 只有当内容明确包含商业推广、产品推荐且有明确的购买引导时，才认定为广告
2. 纯粹的产品介绍、评测、对比，如果没有明确的购买引导，不认定为广告
3. 如果置顶评论中没有商品链接，则更倾向于认定为无广告
4. 广告内容必须与置顶评论或附加信息中的商品链接标题相对应
5. 一段广告的检测需要完整：包含商品介绍前的转折性引入，介绍商品的特点、优势、适用场景、价格、购买渠道等信息，以及商品介绍后的引导购买部分

字幕内容：${JSON.stringify(captions)}

请严格按照检测规则进行判断，以json格式输出（无注释）：
{
  "exist": <bool. true表示存在广告，false表示不存在广告>,
  "good_name": <list[string]. 广告的商品名称>,
  "index_lists": <list[list[int]]. 二维数组，每一行是[start, end]，表示一段*完整*广告的开头结尾字幕index，包含商品介绍前的引入和结尾后的推荐性、煽动购买的语言>
}`;
}

function cleanText(text) {
  return String(text)
    .replace(/```[\s\S]*?```/g, (m) => m.replace(/```/g, ''))
    .replace(/^[\s\S]*?({[\s\S]*})[\s\S]*$/m, '$1')
    .replace(/[\u201C\u201D\u201E\u201F\u2033]/g, '"')
    .replace(/[\u2018\u2019\u2032]/g, "'");
}

function tryParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    const m = String(text).match(/{[\s\S]*}/);
    if (!m) return null;
    try {
      return JSON.parse(
        m[0].replace(/[\u201C\u201D\u201E\u201F\u2033]/g, '"').replace(/[\u2018\u2019\u2032]/g, "'"),
      );
    } catch {
      return null;
    }
  }
}

/**
 * 解析模型返回的 JSON，校验并归一化广告字幕区间
 * @param {string} raw 模型原始输出
 * @param {number} captionCount 字幕条数，用于越界裁剪
 */
export function parseResult(raw, captionCount = 0) {
  const empty = { exist: false, good_name: [], index_lists: [] };
  if (!raw || typeof raw !== 'string') return empty;

  const parsed = tryParse(cleanText(raw));
  if (!parsed || typeof parsed !== 'object') return empty;
  if (typeof parsed.exist !== 'boolean') {
    throw new Error('返回数据格式错误：exist 不是布尔值');
  }

  const result = {
    exist: parsed.exist,
    good_name: Array.isArray(parsed.good_name) ? parsed.good_name.filter((x) => typeof x === 'string') : [],
    index_lists: Array.isArray(parsed.index_lists) ? parsed.index_lists : [],
  };

  const limit = Math.max(0, Math.floor(captionCount));
  const ranges = [];
  for (const item of result.index_lists) {
    if (!Array.isArray(item) || item.length !== 2) continue;
    let [start, end] = item;
    if (typeof start !== 'number' || typeof end !== 'number') continue;
    start = Math.max(0, Math.floor(start));
    end = Math.max(0, Math.floor(end));
    if (limit > 0) {
      start = Math.min(start, limit - 1);
      end = Math.min(end, limit - 1);
    }
    if (start > end) [start, end] = [end, start];
    ranges.push([start, end]);
  }
  ranges.sort((a, b) => a[0] - b[0] || a[1] - b[1]);

  // 合并相邻 / 重叠区间
  const merged = [];
  for (const r of ranges) {
    if (!merged.length || r[0] > merged[merged.length - 1][1] + 1) merged.push([r[0], r[1]]);
    else merged[merged.length - 1][1] = Math.max(merged[merged.length - 1][1], r[1]);
  }

  result.index_lists = merged;
  if (!result.index_lists.length && result.exist === true) result.exist = false;
  return result;
}

/** 字幕下标区间 → 秒区间（用首条字幕的 from 和末条字幕的 to） */
export function index2second(indexLists, captionBody) {
  return indexLists.map(([start, end]) => [
    captionBody[start]?.from || 0,
    captionBody[end]?.to || 0,
  ]);
}

export function second2time(seconds) {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = Math.floor(s % 60);
  const mm = String(m).padStart(2, '0');
  const ss = String(sec).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** 字幕数组 → { index: content }，对齐插件发给模型的 captions 结构 */
export function toCaptionsMap(body) {
  const map = {};
  body.forEach((item, i) => {
    map[i] = item.content;
  });
  return map;
}
