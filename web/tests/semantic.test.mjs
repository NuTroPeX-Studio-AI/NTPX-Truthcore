import test from "node:test";
import assert from "node:assert/strict";
import { deepVerifyClaim, EmbeddingIndex, semanticVerify } from "../src/semantic.mjs";

test("semantic verification does not invent scores without a provider", async () => {
  const result = await semanticVerify("Paris is in France", "Paris is in France");
  assert.equal(result.available, false);
  assert.equal(result.entailment, 0);
  assert.equal(result.contradiction, 0);
});

test("deep verifier passes strong entailment and blocks contradiction", async () => {
  const evidence = { id: "1", content: "Paris is in France", trust: 1 };
  const pass = await deepVerifyClaim("Paris is in France", evidence, async () => ({
    available: true, entailment: 0.97, contradiction: 0.01, provider: "fixture",
  }));
  assert.equal(pass.releasable, true);

  const block = await deepVerifyClaim("Paris is in France", evidence, async () => ({
    available: true, entailment: 0.8, contradiction: 0.9, provider: "fixture",
  }));
  assert.equal(block.releasable, false);
  assert.match(block.reason, /contradiction/i);
});

test("embedding index fails closed and ranks provider vectors", async () => {
  const empty = new EmbeddingIndex();
  assert.deepEqual(await empty.search("Android"), []);

  const provider = async (text) => ({
    available: true,
    values: /android/i.test(text) ? [1, 0] : [0, 1],
  });
  const index = new EmbeddingIndex(provider);
  assert.equal(await index.add("android", "Android native runtime"), true);
  assert.equal(await index.add("other", "Other topic"), true);
  const results = await index.search("Android app");
  assert.equal(results[0].id, "android");
});
