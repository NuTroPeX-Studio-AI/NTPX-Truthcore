import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createRateLimiter, createTruthCoreServer, sanitizeClientEvidence } from "../src/http.mjs";

const publicDir = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "public");

async function withServer(options, fn) {
  const server = createTruthCoreServer({ publicDir, allowedHosts: new Set(), ...options });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await fn(base);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

const postJson = (url, body, headers = {}) => fetch(url, {
  method: "POST",
  headers: { "content-type": "application/json", ...headers },
  body: JSON.stringify(body),
});

test("health reports version and security headers are set", async () => {
  await withServer({}, async (base) => {
    const response = await fetch(`${base}/api/health`);
    const body = await response.json();
    assert.equal(body.ok, true);
    assert.equal(body.version, "1.1.0");
    assert.match(response.headers.get("content-security-policy"), /default-src 'self'/);
    assert.equal(response.headers.get("x-frame-options"), "DENY");
    assert.equal(response.headers.get("strict-transport-security"), null);
  });
});

test("HSTS is opt-in", async () => {
  await withServer({ hsts: true }, async (base) => {
    const response = await fetch(`${base}/api/health`);
    assert.match(response.headers.get("strict-transport-security"), /max-age/);
  });
});

test("unknown API routes return JSON 404 instead of static fallthrough", async () => {
  await withServer({}, async (base) => {
    const response = await postJson(`${base}/api/nope`, {});
    assert.equal(response.status, 404);
    assert.equal((await response.json()).ok, false);
  });
});

test("API requires JSON content type and POST", async () => {
  await withServer({}, async (base) => {
    const wrongType = await fetch(`${base}/api/chat`, { method: "POST", headers: { "content-type": "text/plain" }, body: "{}" });
    assert.equal(wrongType.status, 415);
    const wrongMethod = await fetch(`${base}/api/chat`);
    assert.equal(wrongMethod.status, 405);
  });
});

test("chat works locally without a provider and abstains on open questions", async () => {
  await withServer({}, async (base) => {
    const help = await (await postJson(`${base}/api/chat`, { message: "help" })).json();
    assert.equal(help.status, "VERIFIED");
    const open = await (await postJson(`${base}/api/chat`, { message: "Who won the match?" })).json();
    assert.equal(open.status, "ABSTAINED");
  });
});

test("provider proxy refuses hosts outside the allowlist", async () => {
  await withServer({}, async (base) => {
    const response = await postJson(`${base}/api/provider/test`, { provider: { baseUrl: "https://evil.example/v1", model: "m" } });
    assert.equal(response.status, 400);
    assert.match((await response.json()).error, /not in TRUTHCORE_ALLOWED_PROVIDER_HOSTS/);
  });
});

test("rate limiter returns 429 once a client exhausts its bucket", async () => {
  await withServer({ rateLimit: { capacity: 2, refillPerSecond: 0 } }, async (base) => {
    assert.equal((await postJson(`${base}/api/chat`, { message: "help" })).status, 200);
    assert.equal((await postJson(`${base}/api/chat`, { message: "help" })).status, 200);
    const limited = await postJson(`${base}/api/chat`, { message: "help" });
    assert.equal(limited.status, 429);
    assert.equal(limited.headers.get("retry-after"), "5");
    // Health stays available for load balancers.
    assert.equal((await fetch(`${base}/api/health`)).status, 200);
  });
});

test("static serving blocks traversal and malformed encodings", async () => {
  await withServer({}, async (base) => {
    assert.equal((await fetch(`${base}/%2e%2e/server.mjs`)).status === 200, false);
    assert.equal((await fetch(`${base}/%E0%A4%A`)).status, 400);
    const app = await fetch(`${base}/app`);
    assert.equal(app.status, 200);
    assert.equal(app.headers.get("cache-control"), "no-cache");
  });
});

test("oversized and invalid JSON bodies are rejected", async () => {
  await withServer({}, async (base) => {
    const big = await postJson(`${base}/api/chat`, { message: "x".repeat(70000) });
    assert.equal(big.status, 413);
    const invalid = await fetch(`${base}/api/chat`, { method: "POST", headers: { "content-type": "application/json" }, body: "{" });
    assert.equal(invalid.status, 400);
  });
});

test("client evidence cannot impersonate built-in evidence or exceed trust caps", () => {
  const [item] = sanitizeClientEvidence([{ id: "x", content: "c", trust: 5, independentKey: "web-architecture", label: "a\nb", sourceUri: "javascript:alert(1)" }]);
  assert.equal(item.trust, 0.9);
  assert.equal(item.independentKey, "client:web-architecture");
  assert.equal(item.label, "a b");
  assert.equal(item.sourceUri, undefined);
});

test("rate limiter refills over time", async () => {
  const limiter = createRateLimiter({ capacity: 1, refillPerSecond: 1000 });
  assert.equal(limiter.take("a"), true);
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(limiter.take("a"), true);
});
