import test from "node:test";
import assert from "node:assert/strict";
import { deletionTerms, matchesEveryWord } from "../public/store.js";

test("deletion matching keeps short words and compares whole words", () => {
  const terms = deletionTerms("project X");
  assert.deepEqual([...terms], ["project", "x"]);
  assert.equal(matchesEveryWord(terms, "Notes on Project X launch"), true);
  assert.equal(matchesEveryWord(terms, "Notes on Project Y launch"), false);
  assert.equal(matchesEveryWord(terms, "Project Xenon roadmap"), false);
  assert.equal(matchesEveryWord(deletionTerms(""), "anything"), false);
});
