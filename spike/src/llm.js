// LLM 调用：OpenAI 兼容 /chat/completions（覆盖 OpenAI、DeepSeek、智谱、Kimi、硅基流动等）
// 从环境变量读取，避免在仓库里落盘密钥

export function llmConfig() {
  return {
    baseUrl: process.env.VAG_LLM_BASE_URL || '',
    apiKey: process.env.VAG_LLM_API_KEY || '',
    model: process.env.VAG_LLM_MODEL || '',
  };
}

export function llmConfigured() {
  const c = llmConfig();
  return Boolean(c.baseUrl && c.apiKey && c.model);
}

export async function invokeLLM(systemPrompt, userPrompt, { maxTokens = 8192, temperature = 0 } = {}) {
  const c = llmConfig();
  if (!llmConfigured()) {
    throw new Error('未配置 LLM：请设置 VAG_LLM_BASE_URL / VAG_LLM_API_KEY / VAG_LLM_MODEL');
  }
  const url = `${c.baseUrl.replace(/\/$/, '')}/chat/completions`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${c.apiKey}` },
    body: JSON.stringify({
      model: c.model,
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userPrompt },
      ],
      max_tokens: maxTokens,
      temperature,
    }),
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`LLM HTTP ${res.status}: ${body.slice(0, 300)}`);
  }
  const json = await res.json();
  const text = json?.choices?.[0]?.message?.content;
  if (typeof text !== 'string' || !text.trim()) throw new Error('LLM 未返回文本内容');
  return text.trim();
}
