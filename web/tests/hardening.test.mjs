import test from "node:test";
import assert from "node:assert/strict";
import { isPrivateAddress, isBlockedHostname, guardedLookup } from "../src/netguard.mjs";
import { createEmbeddingProvider, createNliProvider, createProvider, parseNliScores, validateProviderConfig } from "../src/provider.mjs";
import { statements, verifyClaims, verifyClaimsDeep } from "../src/claimlock.mjs";
import { respond } from "../src/conversation.mjs";
import { cachedEmbedder, rerankByEmbedding } from "../src/semantic.mjs";

const publicResolver = async () => ["93.184.216.34"];
const allow = { allowedHosts: new Set(["api.example.com"]), resolveHost: publicResolver };
const paris = [{ id: "p", content: "Paris is the capital of France.", trust: 1 }];

function fakeFetch(handler) {
  const calls = [];
  const impl = async (url, init) => {
    calls.push({ url: String(url), body: JSON.parse(init.body), headers: init.headers });
    const { status = 200, body } = await handler(String(url), JSON.parse(init.body));
    return { status, ok: status >= 200 && status < 300, text: async () => JSON.stringify(body) };
  };
  return { impl, calls };
}

// ---------- network guard ----------

test("private, mapped, NAT64 and reserved addresses are blocked", () => {
  for (const address of [
    "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
    "198.18.0.1", "224.0.0.1", "::1", "::", "fe80::1", "fd00::1", "::ffff:127.0.0.1", "::ffff:169.254.169.254",
    "64:ff9b::a9fe:a9fe", "2002:7f00:1::1", "ff02::1", "not-an-ip",
  ]) {
    assert.equal(isPrivateAddress(address), true, address);
  }
  for (const address of ["93.184.216.34", "8.8.8.8", "2606:4700:4700::1111", "::ffff:8.8.8.8"]) {
    assert.equal(isPrivateAddress(address), false, address);
  }
});

test("internal hostnames are blocked", () => {
  for (const host of ["localhost", "api.localhost", "printer.local", "metadata.google.internal", "db.internal", "nas.home.arpa"]) {
    assert.equal(isBlockedHostname(host), true, host);
  }
  assert.equal(isBlockedHostname("api.openai.com"), false);
});

test("guarded lookup refuses blocked hostnames before DNS", async () => {
  const error = await new Promise((resolve) => guardedLookup("localhost", {}, (err) => resolve(err)));
  assert.equal(error.code, "ETRUTHCORE_BLOCKED");
});

test("provider validation rejects hosts that resolve privately", async () => {
  const result = await validateProviderConfig(
    { baseUrl: "https://api.example.com/v1", model: "m" },
    { allowedHosts: new Set(["api.example.com"]), resolveHost: async () => ["93.184.216.34", "10.0.0.5"] },
  );
  assert.equal(result.ok, false);
  assert.match(result.error, /private/i);
});

// ---------- provider factories ----------

test("chat provider handles content-part arrays and blocks redirects", async () => {
  const parts = fakeFetch(async () => ({ body: { choices: [{ message: { content: [{ type: "text", text: "Hel" }, { type: "text", text: "lo" }] } }] } }));
  const ok = await createProvider({ baseUrl: "https://api.example.com/v1", model: "m" }, { ...allow, fetchImpl: parts.impl })({ systemPrompt: "s", userPrompt: "u" });
  assert.deepEqual(ok, { success: true, text: "Hello" });
  assert.equal(parts.calls[0].url, "https://api.example.com/v1/chat/completions");

  const redirect = fakeFetch(async () => ({ status: 302, body: {} }));
  const blocked = await createProvider({ baseUrl: "https://api.example.com/v1", model: "m" }, { ...allow, fetchImpl: redirect.impl })({ systemPrompt: "s", userPrompt: "u" });
  assert.equal(blocked.success, false);
  assert.match(blocked.error, /redirect/i);
});

test("chat provider never returns null content as text and redacts keys", async () => {
  const nullContent = fakeFetch(async () => ({ body: { choices: [{ message: { content: null } }] } }));
  const result = await createProvider({ baseUrl: "https://api.example.com/v1", model: "m" }, { ...allow, fetchImpl: nullContent.impl })({ systemPrompt: "s", userPrompt: "u" });
  assert.equal(result.success, false);

  const leaky = fakeFetch(async () => ({ status: 401, body: { error: { message: "bad key sk-secret-123" } } }));
  const failed = await createProvider({ baseUrl: "https://api.example.com/v1", model: "m", apiKey: "sk-secret-123" }, { ...allow, fetchImpl: leaky.impl })({ systemPrompt: "s", userPrompt: "u" });
  assert.equal(failed.success, false);
  assert.doesNotMatch(failed.error, /sk-secret-123/);
});

test("embedding provider calls /embeddings and fails closed", async () => {
  const embeddings = fakeFetch(async () => ({ body: { data: [{ embedding: [0.1, 0.2, 0.3] }] } }));
  const embed = createEmbeddingProvider({ baseUrl: "https://api.example.com/v1/chat/completions", model: "m", embeddingModel: "e" }, { ...allow, fetchImpl: embeddings.impl });
  const vector = await embed("hello");
  assert.equal(vector.available, true);
  assert.deepEqual(vector.values, [0.1, 0.2, 0.3]);
  assert.equal(embeddings.calls[0].url, "https://api.example.com/v1/embeddings");

  const none = await createEmbeddingProvider({ baseUrl: "https://api.example.com/v1", model: "m" }, allow)("hello");
  assert.equal(none.available, false);

  const garbage = fakeFetch(async () => ({ body: { data: [{ embedding: ["x"] }] } }));
  const bad = await createEmbeddingProvider({ baseUrl: "https://api.example.com/v1", model: "m", embeddingModel: "e" }, { ...allow, fetchImpl: garbage.impl })("hello");
  assert.equal(bad.available, false);
});

test("NLI judge parses JSON verdicts and fails closed on prose", async () => {
  assert.deepEqual(parseNliScores("Verdict: {\"entailment\": 1.4, \"contradiction\": -0.1}"), { entailment: 1, contradiction: 0 });
  assert.equal(parseNliScores("it is entailed"), null);

  const judge = fakeFetch(async (_, body) => ({ body: { choices: [{ message: { content: body.model === "judge" ? "{\"entailment\":0.93,\"contradiction\":0.02}" : "nope" } }] } }));
  const nli = createNliProvider({ baseUrl: "https://api.example.com/v1", model: "drafter", nliModel: "judge" }, { ...allow, fetchImpl: judge.impl });
  const scores = await nli("Paris is in France", "Paris is in France.");
  assert.equal(scores.available, true);
  assert.equal(scores.entailment, 0.93);
  assert.equal(judge.calls[0].body.model, "judge");

  const prose = fakeFetch(async () => ({ body: { choices: [{ message: { content: "Probably yes." } }] } }));
  const unavailable = await createNliProvider({ baseUrl: "https://api.example.com/v1", model: "m" }, { ...allow, fetchImpl: prose.impl })("a", "b");
  assert.equal(unavailable.available, false);
});

// ---------- ClaimLock hardening ----------

test("orphan citation fragments are re-attached", () => {
  const result = verifyClaims("Paris is the capital of France. [S1]", paris);
  assert.equal(result.claims.length, 1);
  assert.equal(result.claims[0].status, "SUPPORTED");
});

test("stopword-only claims cannot ride a citation", () => {
  const result = verifyClaims("It is. [S1]", paris);
  assert.equal(result.released, 0);
  assert.match(result.claims[0].reason, /no checkable content/i);
});

test("polarity is judged against the best-matching source sentence", () => {
  const source = [{ id: "d", content: "Paris is the capital of France.\nThe museum is not open on Tuesdays.", trust: 1 }];
  assert.equal(verifyClaims("Paris is the capital of France [S1].", source).claims[0].status, "SUPPORTED");
  const flipped = [{ id: "d", content: "The service does not store audio recordings.", trust: 1 }];
  assert.equal(verifyClaims("The service does store audio recordings [S1].", flipped).claims[0].status, "CONTRADICTED");
});

test("quantities: separators normalized, percentages exact", () => {
  const archive = [{ id: "a", content: "The archive holds 12000 records.", trust: 1 }];
  assert.equal(verifyClaims("The archive holds 12,000 records [S1].", archive).claims[0].status, "SUPPORTED");
  const uptime = [{ id: "u", content: "Uptime was 99.9% last quarter.", trust: 1 }];
  assert.equal(verifyClaims("Uptime was 99.9% last quarter [S1].", uptime).released, 1);
  assert.equal(verifyClaims("Uptime was 99.5% last quarter [S1].", uptime).released, 0);
});

test("abbreviations do not split statements", () => {
  assert.equal(statements("Use a provider, e.g. a local model [S1]. Then verify [S2].").length, 2);
});

test("semantic gate is additive: it can withhold but never release", async () => {
  const alwaysEntails = async () => ({ available: true, entailment: 1, contradiction: 0, provider: "fixture" });
  const rejected = await verifyClaimsDeep("Berlin hosts the parliament of Germany [S1].", paris, { nli: alwaysEntails });
  assert.equal(rejected.released, 0);

  const weak = async () => ({ available: true, entailment: 0.3, contradiction: 0.05, provider: "fixture" });
  const withheld = await verifyClaimsDeep("Paris is the capital of France [S1].", paris, { nli: weak });
  assert.equal(withheld.claims[0].status, "UNSUPPORTED");

  const strong = async () => ({ available: true, entailment: 0.97, contradiction: 0.01, provider: "fixture" });
  const passed = await verifyClaimsDeep("Paris is the capital of France [S1].", paris, { nli: strong });
  assert.equal(passed.claims[0].method, "semantic+deterministic");

  const contradicts = async () => ({ available: true, entailment: 0.9, contradiction: 0.95, provider: "fixture" });
  const contradicted = await verifyClaimsDeep("Paris is the capital of France [S1].", paris, { nli: contradicts });
  assert.equal(contradicted.claims[0].status, "CONTRADICTED");
});

test("required semantic gate withholds when unavailable; optional gate does not", async () => {
  const down = async () => ({ available: false, reason: "offline" });
  const required = await verifyClaimsDeep("Paris is the capital of France [S1].", paris, { nli: down, requireSemantic: true });
  assert.equal(required.released, 0);
  const optional = await verifyClaimsDeep("Paris is the capital of France [S1].", paris, { nli: down });
  assert.equal(optional.released, 1);
});

// ---------- conversation wiring ----------

test("conversation applies the NLI gate and returns a claim audit", async () => {
  const evidence = [{ id: "s", label: "Saved fact", content: "The project codename is Orion.", trust: 0.9, independentKey: "s" }];
  const provider = async () => ({ success: true, text: "The project codename is Orion [S4]." });
  const rejecting = async () => ({ available: true, entailment: 0.1, contradiction: 0, provider: "fixture" });
  const reply = await respond("What is the project codename?", { provider, evidence, nli: rejecting });
  assert.equal(reply.status, "ABSTAINED");
  assert.equal(reply.claims.length, 1);
  assert.equal(reply.semantic, "applied");
});

test("embedding ranking reorders retrieved evidence but keeps base sources fixed", async () => {
  const evidence = [
    { id: "cook", label: "Recipe", content: "Simmer the tomato sauce for twenty minutes.", trust: 0.9, independentKey: "cook" },
    { id: "code", label: "Project", content: "The project codename is Orion.", trust: 0.9, independentKey: "code" },
  ];
  const embed = async (text) => ({ available: true, values: /codename|orion/i.test(text) ? [1, 0] : [0, 1] });
  let packet = "";
  const provider = async ({ userPrompt }) => {
    packet = userPrompt;
    return { success: true, text: "The project codename is Orion [S4]." };
  };
  const reply = await respond("What is the project codename?", { provider, evidence, embed });
  assert.equal(reply.status, "VERIFIED");
  assert.match(packet, /\[S1] TruthCore architecture/);
  assert.match(packet, /\[S4] Project/);
});

test("rerank falls back to original order when embeddings fail", async () => {
  const items = [{ label: "a", content: "x" }, { label: "b", content: "y" }];
  assert.deepEqual(await rerankByEmbedding("q", items, async () => ({ available: false })), items);
  assert.deepEqual(await rerankByEmbedding("q", items, null), items);
});

test("cached embedder reuses vectors", async () => {
  let calls = 0;
  const embed = cachedEmbedder(async () => { calls += 1; return { available: true, values: [1] }; });
  await embed("same");
  await embed("same");
  assert.equal(calls, 1);
});

test("guarded transport rejects reserved destinations without crashing", async () => {
  const { guardedPost } = await import("../src/netguard.mjs");
  for (const url of ["https://127.0.0.1/v1", "https://[::1]/v1", "https://[::ffff:10.0.0.1]/v1", "https://localhost/v1", "http://example.com/v1"]) {
    await assert.rejects(guardedPost(url, { body: "{}" }), url);
  }
});
