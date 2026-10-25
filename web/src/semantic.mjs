import { effectiveStrength, sanitizeEvidence } from "./evidence.mjs";

export function clampScore(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(0, Math.min(1, number)) : 0;
}

export async function semanticVerify(claim, evidenceText, provider = null) {
  const c = String(claim ?? "").trim();
  const e = String(evidenceText ?? "").trim();
  if (!c || !e) {
    return { available: false, entailment: 0, contradiction: 0, provider: "unavailable", reason: "Claim or evidence is empty" };
  }
  if (typeof provider !== "function") {
    return { available: false, entailment: 0, contradiction: 0, provider: "unavailable", reason: "No semantic/NLI provider is configured" };
  }
  const result = await provider(c, e);
  if (!result?.available) {
    return {
      available: false,
      entailment: 0,
      contradiction: 0,
      provider: String(result?.provider || "unavailable"),
      reason: String(result?.reason || "Semantic provider is unavailable"),
    };
  }
  return {
    available: true,
    entailment: clampScore(result.entailment),
    contradiction: clampScore(result.contradiction),
    provider: String(result.provider || "semantic-provider"),
    reason: String(result.reason || "Semantic provider returned scores"),
  };
}

export async function deepVerifyClaim(
  claim,
  evidence,
  provider = null,
  { entailmentThreshold = 0.8, contradictionThreshold = 0.2 } = {},
) {
  if (effectiveStrength(evidence) <= 0) {
    return { releasable: false, semanticAvailable: false, reason: "Evidence is expired, quarantined, or untrusted" };
  }
  const sanitized = sanitizeEvidence(evidence?.content || "");
  if (sanitized.quarantined || !sanitized.text) {
    return { releasable: false, semanticAvailable: false, reason: "Evidence failed injection isolation" };
  }
  const semantic = await semanticVerify(claim, sanitized.text, provider);
  if (!semantic.available) {
    return { releasable: false, semanticAvailable: false, reason: semantic.reason, semantic };
  }
  if (semantic.contradiction >= contradictionThreshold) {
    return { releasable: false, semanticAvailable: true, reason: "Semantic verifier detected contradiction", semantic };
  }
  if (semantic.entailment < entailmentThreshold) {
    return { releasable: false, semanticAvailable: true, reason: "Semantic entailment is below release threshold", semantic };
  }
  return { releasable: true, semanticAvailable: true, reason: "Semantic evidence passed deep verification", semantic };
}

export class EmbeddingIndex {
  constructor(provider = null) {
    this.provider = provider;
    this.vectors = new Map();
  }

  async add(id, text) {
    if (typeof this.provider !== "function") return false;
    const result = await this.provider(String(text));
    if (!result?.available || !Array.isArray(result.values) || !result.values.length) return false;
    this.vectors.set(String(id), result.values.map(Number));
    return true;
  }

  async search(text, limit = 8) {
    if (typeof this.provider !== "function") return [];
    const query = await this.provider(String(text));
    if (!query?.available || !Array.isArray(query.values) || !query.values.length) return [];
    return [...this.vectors.entries()]
      .map(([id, vector]) => ({ id, score: cosine(query.values, vector) }))
      .filter((row) => Number.isFinite(row.score))
      .sort((a, b) => b.score - a.score)
      .slice(0, limit);
  }
}

function cosine(a, b) {
  if (!Array.isArray(a) || !Array.isArray(b) || !a.length || a.length !== b.length) return Number.NaN;
  let dot = 0;
  let aa = 0;
  let bb = 0;
  for (let i = 0; i < a.length; i += 1) {
    const x = Number(a[i]);
    const y = Number(b[i]);
    if (!Number.isFinite(x) || !Number.isFinite(y)) return Number.NaN;
    dot += x * y;
    aa += x * x;
    bb += y * y;
  }
  if (!aa || !bb) return Number.NaN;
  return dot / (Math.sqrt(aa) * Math.sqrt(bb));
}

/**
 * Wraps an embedding provider with a bounded LRU cache keyed by text.
 */
export function cachedEmbedder(provider, maxEntries = 512) {
  if (typeof provider !== "function") return null;
  const cache = new Map();
  return async (text) => {
    const key = String(text);
    if (cache.has(key)) {
      const hit = cache.get(key);
      cache.delete(key);
      cache.set(key, hit);
      return hit;
    }
    const result = await provider(key);
    if (result?.available) {
      cache.set(key, result);
      if (cache.size > maxEntries) cache.delete(cache.keys().next().value);
    }
    return result;
  };
}

/**
 * Re-orders evidence by cosine similarity to the query. Ranking only changes
 * which evidence reaches the model; it never decides whether a claim is
 * released. Falls back to the original order when embeddings are unavailable.
 */
export async function rerankByEmbedding(query, evidence, provider, { concurrency = 4 } = {}) {
  const items = Array.isArray(evidence) ? evidence : [];
  if (typeof provider !== "function" || items.length < 2) return items;
  const queryVector = await provider(String(query));
  if (!queryVector?.available || !Array.isArray(queryVector.values)) return items;

  const scores = new Array(items.length).fill(Number.NEGATIVE_INFINITY);
  let cursor = 0;
  const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor++;
      const item = items[index];
      const vector = await provider(`${item.label || ""}: ${item.content || ""}`).catch(() => null);
      const score = vector?.available ? cosine(queryVector.values, vector.values) : Number.NaN;
      if (Number.isFinite(score)) scores[index] = score;
    }
  });
  await Promise.all(workers);
  return items
    .map((item, index) => ({ item, index, score: scores[index] }))
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .map(({ item }) => item);
}

export { cosine };
