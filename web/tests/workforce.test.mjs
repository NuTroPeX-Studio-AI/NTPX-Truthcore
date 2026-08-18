import test from "node:test";
import assert from "node:assert/strict";
import { runWorkforce } from "../src/workforce.mjs";

test("workforce fails closed without a model provider", async () => {
  const result = await runWorkforce("review this");
  assert.equal(result.ok, false);
  assert.equal(result.status, "ABSTAINED");
});

test("workforce runs planner critic reviewer and stays generated", async () => {
  let calls = 0;
  const provider = async () => {
    calls += 1;
    return {
      success: true,
      text: calls === 1
        ? "PLAN: identify assumptions."
        : calls === 2
          ? "CRITIQUE: validate dependencies."
          : "REVIEW: bounded recommendation.",
    };
  };
  const result = await runWorkforce("finish the feature", provider);
  assert.equal(result.ok, true);
  assert.equal(result.status, "TEAM_GENERATED");
  assert.equal(result.stages.length, 3);
  assert.match(result.text, /REVIEW/);
  assert.equal(calls, 3);
});
