import test from "node:test";
import assert from "node:assert/strict";
import { respond } from "../src/conversation.mjs";

const fixture = (text) => async () => ({ success: true, text });

test("help is locally verified", async () => {
  const reply = await respond("help");
  assert.equal(reply.status, "VERIFIED");
  assert.equal(reply.verified, true);
  assert.match(reply.text, /web app shell/i);
});

test("unsupported factual request abstains without provider", async () => {
  const reply = await respond("Who won tonight's game?");
  assert.equal(reply.status, "ABSTAINED");
  assert.equal(reply.verified, false);
});

test("creative request uses provider but is labeled generated", async () => {
  const reply = await respond("write a short greeting", { provider: fixture("Hello from TruthCore.") });
  assert.equal(reply.status, "GENERATED");
  assert.equal(reply.verified, false);
  assert.equal(reply.text, "Hello from TruthCore.");
});

test("unsupported factual model output is withheld", async () => {
  const reply = await respond("What is the moon made of?", { provider: fixture("The moon is made of cheese.") });
  assert.equal(reply.status, "ABSTAINED");
  assert.match(reply.text, /enough verified evidence/i);
});
