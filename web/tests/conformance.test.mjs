import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { verifyClaims } from "../src/claimlock.mjs";

// Shared with app/src/test/.../ClaimLockConformanceTest.kt — both platforms must agree.
const fixture = JSON.parse(readFileSync(new URL("../../conformance/claimlock-cases.json", import.meta.url), "utf8"));

for (const testCase of fixture.cases) {
  test(`conformance: ${testCase.name}`, () => {
    const evidence = testCase.evidence.map((item, index) => ({ id: `e${index + 1}`, ...item }));
    const result = verifyClaims(testCase.draft, evidence, fixture.minSupport);
    assert.deepEqual(result.claims.map((claim) => claim.status), testCase.expect);
  });
}
