import dns from "node:dns/promises";
import net from "node:net";

export function parseAllowedHosts(value = process.env.TRUTHCORE_ALLOWED_PROVIDER_HOSTS ?? "") {
  return new Set(String(value).split(",").map((item) => item.trim().toLowerCase()).filter(Boolean));
}

export async function validateProviderConfig(config, { allowedHosts = parseAllowedHosts(), resolveHost = defaultResolveHost } = {}) {
  const model = String(config?.model ?? "").trim();
  if (!model) return { ok: false, error: "Model name is required" };

  let url;
  try {
    url = new URL(String(config?.baseUrl ?? "").trim());
  } catch {
    return { ok: false, error: "Base URL is invalid" };
  }
  if (url.protocol !== "https:") return { ok: false, error: "Only HTTPS model endpoints are allowed" };
  if (!url.hostname) return { ok: false, error: "Base URL must include a host" };
  if (url.username || url.password) return { ok: false, error: "Credentials must not be embedded in the URL" };

  const host = url.hostname.toLowerCase();
  if (!allowedHosts.has("*") && !allowedHosts.has(host)) {
    return { ok: false, error: `Provider host ${host} is not in TRUTHCORE_ALLOWED_PROVIDER_HOSTS` };
  }

  if (isBlockedHostname(host)) return { ok: false, error: "Local, metadata, and private-network provider hosts are blocked" };
  const addresses = await resolveHost(host).catch(() => []);
  if (!addresses.length) return { ok: false, error: "Provider host could not be resolved" };
  if (addresses.some((address) => isPrivateAddress(address))) {
    return { ok: false, error: "Provider host resolves to a private or reserved network address" };
  }

  return { ok: true, url, model, host };
}

export function createProvider(config, options = {}) {
  return async ({ systemPrompt, userPrompt, temperature }) => {
    const validation = await validateProviderConfig(config, options);
    if (!validation.ok) return { success: false, error: validation.error };

    const endpoint = chatUrl(validation.url);
    const apiKey = String(config?.apiKey ?? "");
    const fetchImpl = options.fetchImpl ?? fetch;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 30000);

    try {
      const response = await fetchImpl(endpoint, {
        method: "POST",
        redirect: "manual",
        signal: controller.signal,
        headers: {
          "content-type": "application/json; charset=utf-8",
          accept: "application/json",
          ...(apiKey ? { authorization: `Bearer ${apiKey}` } : {}),
        },
        body: JSON.stringify({
          model: validation.model,
          temperature: Math.max(0, Math.min(1, Number(temperature ?? 0))),
          stream: false,
          messages: [
            { role: "system", content: systemPrompt },
            { role: "user", content: userPrompt },
          ],
        }),
      });

      if (response.status >= 300 && response.status < 400) {
        return { success: false, error: "Model endpoint redirects are blocked" };
      }

      const body = await response.text();
      if (!response.ok) {
        const message = safeProviderMessage(body, apiKey) || `HTTP ${response.status}`;
        return { success: false, error: `Model request failed: ${message}` };
      }

      let root;
      try {
        root = JSON.parse(body);
      } catch {
        return { success: false, error: "Model provider returned invalid JSON" };
      }
      const text = String(root?.choices?.[0]?.message?.content ?? "").trim();
      return text ? { success: true, text } : { success: false, error: "Model returned no text" };
    } catch (error) {
      const message = error?.name === "AbortError" ? "Request timed out" : sanitizeError(error?.message, apiKey);
      return { success: false, error: `Model request failed: ${message}` };
    } finally {
      clearTimeout(timer);
    }
  };
}

function chatUrl(baseUrl) {
  const url = new URL(baseUrl.toString());
  const cleanPath = url.pathname.replace(/\/+$/, "");
  url.pathname = cleanPath.endsWith("/chat/completions") ? cleanPath : `${cleanPath}/chat/completions`;
  return url;
}

function safeProviderMessage(body, apiKey) {
  try {
    const parsed = JSON.parse(body);
    return sanitizeError(parsed?.error?.message, apiKey).slice(0, 220);
  } catch {
    return "";
  }
}

function sanitizeError(value, apiKey) {
  const raw = String(value ?? "Provider error");
  return apiKey ? raw.split(apiKey).join("***").slice(0, 220) : raw.slice(0, 220);
}

async function defaultResolveHost(host) {
  const rows = await dns.lookup(host, { all: true, verbatim: true });
  return rows.map((row) => row.address);
}

function isBlockedHostname(host) {
  return host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host === "metadata.google.internal";
}

function isPrivateAddress(address) {
  const family = net.isIP(address);
  if (family === 4) {
    const [a, b] = address.split(".").map(Number);
    return a === 0 || a === 10 || a === 127 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || a >= 224;
  }
  if (family === 6) {
    const normalized = address.toLowerCase();
    return normalized === "::" || normalized === "::1" || normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe8") || normalized.startsWith("fe9") || normalized.startsWith("fea") || normalized.startsWith("feb");
  }
  return true;
}
