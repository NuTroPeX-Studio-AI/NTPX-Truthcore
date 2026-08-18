import { effectiveStrength, sanitizeEvidence } from "./evidence.mjs";

const numberRegex = /\b\d+(?:\.\d+)?%?\b/g;
const tokenRegex = /[a-zA-Z0-9_'-]+/g;
const negativeRegex = /\b(no|not|never|cannot|can't|won't|without|isn't|aren't|doesn't|don't)\b/i;
const stop = new Set(["the", "a", "an", "and", "or", "is", "are", "was", "were", "to", "of", "in", "on", "at", "for", "with", "from", "that", "this", "it", "be", "as", "by"]);

export function verifyClaims(draft, evidence, minSupport = 0.72) {
  const indexed = new Map(evidence.map((item, index) => [index + 1, item]));
  const assessments = [];
  const released = [];

  for (const statement of statements(draft)) {
    const lower = statement.toLowerCase();
    if (lower.startsWith("proposal:")) {
      assessments.push({ text: statement, status: "PROPOSAL", sourceIds: [], reason: "Explicit proposal" });
      released.push(statement);
      continue;
    }
    if (lower.startsWith("unknown:")) {
      assessments.push({ text: statement, status: "UNKNOWN", sourceIds: [], reason: "Explicit unknown" });
      released.push(statement);
      continue;
    }

    const refs = [...statement.matchAll(/\[S(\d+)]/g)].map((match) => Number(match[1]));
    const sources = refs.map((ref) => indexed.get(ref)).filter(Boolean);
    if (!refs.length || sources.length !== refs.length) {
      assessments.push({ text: statement, status: "UNSUPPORTED", sourceIds: refs.map((ref) => `S${ref}`), reason: "No valid bound citation" });
      continue;
    }

    const claim = statement.replace(/\[S(\d+)]/g, "").trim();
    let contradicted = false;
    let strongest = 0;
    let reason = "Insufficient support";

    for (const source of sources) {
      const sanitized = sanitizeEvidence(source.content);
      const effective = sanitized.quarantined ? 0 : effectiveStrength(source);
      if (effective <= 0) {
        reason = "Source expired, quarantined, or untrusted";
        continue;
      }
      const overlapScore = overlap(claim, sanitized.text);
      const claimNums = new Set(claim.match(numberRegex) ?? []);
      const sourceNums = new Set(sanitized.text.match(numberRegex) ?? []);
      if (claimNums.size && !containsAll(sourceNums, claimNums)) {
        if (overlapScore >= 0.6 && sourceNums.size) contradicted = true;
        reason = "Claim introduces or conflicts with a quantity absent from evidence";
        continue;
      }
      if (overlapScore >= 0.6 && negativeRegex.test(claim) !== negativeRegex.test(sanitized.text)) {
        contradicted = true;
        reason = "Claim polarity conflicts with evidence";
        continue;
      }
      strongest = Math.max(strongest, overlapScore * effective);
    }

    if (contradicted) {
      assessments.push({ text: statement, status: "CONTRADICTED", sourceIds: refs.map((ref) => `S${ref}`), reason });
    } else if (strongest >= minSupport) {
      assessments.push({ text: statement, status: "SUPPORTED", sourceIds: refs.map((ref) => `S${ref}`), reason: "Bound evidence passed deterministic truth checks" });
      released.push(statement);
    } else {
      assessments.push({ text: statement, status: "UNSUPPORTED", sourceIds: refs.map((ref) => `S${ref}`), reason });
    }
  }

  const withheld = assessments.filter((item) => item.status === "UNSUPPORTED" || item.status === "CONTRADICTED").length;
  const answer = released.length
    ? `${released.join(" ")}${withheld ? "\n\nSome generated claims were withheld because ClaimLock could not verify them." : ""}`
    : "I don't have enough verified evidence to answer that reliably.";

  return { answer, claims: assessments, released: released.length, withheld };
}

function statements(text) {
  return String(text).trim().split(/(?<=[.!?])\s+|\n+/).map((item) => item.trim()).filter(Boolean);
}

function overlap(claim, source) {
  const c = tokenSet(claim);
  if (!c.size) return 1;
  const s = tokenSet(source);
  let matches = 0;
  for (const token of c) if (s.has(token)) matches += 1;
  return matches / c.size;
}

function tokenSet(value) {
  return new Set((String(value).toLowerCase().match(tokenRegex) ?? []).filter((token) => !stop.has(token)));
}

function containsAll(haystack, needles) {
  for (const item of needles) if (!haystack.has(item)) return false;
  return true;
}
