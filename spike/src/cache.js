// 云端缓存：对齐 CloudCacheService，验证社区共享结果能否直接复用
const DEFAULT_WORKER_URL = 'https://videoadguard-api.0100320.xyz';
const CLIENT_VERSION = '1.4.1';

function workerUrl() {
  return (process.env.VAG_WORKER_URL || DEFAULT_WORKER_URL).replace(/\/$/, '');
}

/**
 * 查询云端缓存（协议：GET /api/cache/{bvid}）
 * @returns {{status:'hit'|'miss'|'error', data?:object, reason?:string}}
 */
export async function fetchRemoteCache(bvid, timeoutMs = 5000) {
  const url = `${workerUrl()}/api/cache/${encodeURIComponent(bvid)}`;
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      headers: { 'Content-Type': 'application/json', 'X-Client-Version': CLIENT_VERSION },
      signal: ac.signal,
    });
    if (!res.ok) return { status: 'error', reason: `HTTP ${res.status} ${res.statusText}` };
    const json = await res.json();
    if (!json?.success) return { status: 'error', reason: json?.error || 'success=false' };
    const data = json.data;
    if (!data?.adTimeRanges) return { status: 'miss' };
    if (data.bvid !== bvid || !Array.isArray(data.adTimeRanges)) {
      return { status: 'error', reason: '云端数据格式异常' };
    }
    return { status: 'hit', data };
  } catch (e) {
    return { status: 'error', reason: e?.name === 'AbortError' ? '请求超时' : String(e.message || e) };
  } finally {
    clearTimeout(timer);
  }
}
