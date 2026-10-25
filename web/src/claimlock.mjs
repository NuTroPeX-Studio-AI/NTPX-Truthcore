import { effectiveStrength, sanitizeEvidence } from "./evidence.mjs";
import { deepVerifyClaim } from "./semantic.mjs";

const citationRegex = /\[S(\d+)]/g;
const numberRegex = /(?<![\w.])(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?%?(?![\w])/g;
const tokenRegex = /[a-zA-Z0-9_'-]+/g;
const negativeRegex = /\b(no|not|never|none|cannot|can't|won't|without|isn't|aren't|wasn't|weren't|doesn't|don't|didn't|hasn't|haven't|neither|nor)\b/i;
const stop = new Set(["the", "a", "an", "and", "or", "is", "are", "was", "were", "to", "of", "in", "on", "at", "for", "with", "from", "that", "this", "it", "be", "as", "by"]);
const abbreviations = ["e.g.", "i.e.", "etc.", "vs.", "mr.", "mrs.", "ms.", "dr.", "prof.", "inc.", "ltd.", "co.", "no.", "fig.", "approx.", "st.", "jr.", "sr.", "u.s.", "u.k."];

export const CONTRADICTION_OVERLAP = 0.6;

/**
 * Deterministic ClaimLock. Every released factual statement must carry a
 * valid [S#] citation whose (sanitized, unexpired) source overlaps the claim,
 * contains every quantity the claim states, and does not flip its polarity.
 */
export function verifyClaims(draft, evidence, minSupport = 0.72) {
  const indexed = new Map(evidence.map((item, index) => [index + 1, item]));
  const assessments = [];

  for (const statement of statements(draft)) {
    assessments.push(assessStatement(statement, indexed, minSupport));
  }
  return summarize(assessments);
}

/**
 * Deterministic ClaimLock followed by an optional semantic/NLI gate.
 * The semantic gate is strictly additive: it can withhold a claim that the
 * deterministic gate supported, but never releases one it rejected.
 */
export async function verifyClaimsDeep(draft, evidence, { nli = null, minSupport = 0.72, requireSemantic = false, thresholds = {} } = {}) {
  const base = verifyClaims(draft, evidence, minSupport);
  if (typeof nli !== "function" && !requireSemantic) return { ...base, semantic: "not-configured" };

  const indexed = new Map(evidence.map((item, index) => [index + 1, item]));
  const assessments = [];
  for (const assessment of base.claims) {
    if (assessment.status !== "SUPPORTED") {
      assessments.push(assessment);
      continue;
    }
    const claim = assessment.text.replace(citationRegex, "").trim();
    // Only sources that individually passed the deterministic gate are judged, so an
    // unrelated co-citation can neither unlock nor stand in for a semantic verdict.
    const sources = (assessment.supportingIds ?? assessment.sourceIds).map((id) => indexed.get(Number(id.slice(1)))).filter(Boolean);
    let passed = false;
    let contradicted = false;
    let completed = 0;
    let unavailableReason = null;
    for (const source of sources) {
      const deep = await deepVerifyClaim(claim, source, nli, thresholds);
      if (!deep.semanticAvailable) {
        unavailableReason = deep.reason;
        continue;
      }
      completed += 1;
      if (/contradiction/i.test(deep.reason)) contradicted = true;
      if (deep.releasable) passed = true;
    }
    if (contradicted) {
      assessments.push({ ...assessment, status: "CONTRADICTED", reason: "Semantic verifier detected contradiction with cited evidence" });
    } else if (passed) {
      assessments.push({ ...assessment, reason: "Bound evidence passed deterministic + semantic truth checks", method: "semantic+deterministic" });
    } else if (completed === 0 && unavailableReason && !requireSemantic) {
      assessments.push({ ...assessment, reason: `${assessment.reason} (semantic gate unavailable: ${unavailableReason})` });
    } else {
      assessments.push({ ...assessment, status: "UNSUPPORTED", reason: completed === 0 && unavailableReason ? `Semantic verification required but unavailable: ${unavailableReason}` : "Semantic entailment is below release threshold" });
    }
  }
  return { ...summarize(assessments), semantic: typeof nli === "function" ? "applied" : "required-unavailable" };
}

function assessStatement(statement, indexed, minSupport) {
  const lower = statement.toLowerCase();
  if (lower.startsWith("proposal:")) return { text: statement, status: "PROPOSAL", sourceIds: [], reason: "Explicit proposal" };
  if (lower.startsWith("unknown:")) return { text: statement, status: "UNKNOWN", sourceIds: [], reason: "Explicit unknown" };

  const refs = [...new Set([...statement.matchAll(citationRegex)].map((match) => Number(match[1])))];
  const sourceIds = refs.map((ref) => `S${ref}`);
  const sources = refs.map((ref) => indexed.get(ref)).filter(Boolean);
  if (!refs.length || sources.length !== refs.length) {
    return { text: statement, status: "UNSUPPORTED", sourceIds, reason: "No valid bound citation" };
  }

  const claim = statement.replace(citationRegex, "").trim();
  const claimTokens = tokenSet(claim);
  if (!claimTokens.size) {
    return { text: statement, status: "UNSUPPORTED", sourceIds, reason: "Claim has no checkable content" };
  }

  const claimNums = numbersIn(claim);
  const claimNegative = negativeRegex.test(claim);
  let contradicted = false;
  let strongest = 0;
  let reason = "Insufficient support";
  const supportingIds = [];

  for (const [position, source] of sources.entries()) {
    const sanitized = sanitizeEvidence(source.content);
    const effective = sanitized.quarantined ? 0 : effectiveStrength(source);
    if (effective <= 0 || !sanitized.text) {
      reason = "Source expired, quarantined, or untrusted";
      continue;
    }

    const wholeOverlap = overlap(claimTokens, sanitized.text);
    const best = bestSentences(claimTokens, sanitized.text);
    const sourceNums = numbersIn(sanitized.text);

    if (claimNums.size && !containsAll(sourceNums, claimNums)) {
      if (best.score >= CONTRADICTION_OVERLAP && best.texts.some((text) => numbersIn(text).size)) contradicted = true;
      reason = "Claim introduces or conflicts with a quantity absent from evidence";
      continue;
    }
    // Every sentence tied for the best match is checked, so the order of sentences
    // in the source cannot hide an explicit contradiction.
    if (best.score >= CONTRADICTION_OVERLAP && best.texts.some((text) => claimNegative !== negativeRegex.test(text))) {
      contradicted = true;
      reason = "Claim polarity conflicts with evidence";
      continue;
    }
    const support = wholeOverlap * effective;
    if (support >= minSupport) supportingIds.push(sourceIds[position]);
    strongest = Math.max(strongest, support);
  }

  if (contradicted) return { text: statement, status: "CONTRADICTED", sourceIds, reason };
  if (strongest >= minSupport) {
    return { text: statement, status: "SUPPORTED", sourceIds, supportingIds, reason: "Bound evidence passed deterministic truth checks", method: "deterministic", support: round(strongest) };
  }
  return { text: statement, status: "UNSUPPORTED", sourceIds, reason, support: round(strongest) };
}

function summarize(assessments) {
  const released = assessments
    .filter((item) => item.status === "SUPPORTED" || item.status === "PROPOSAL" || item.status === "UNKNOWN")
    .map((item) => item.text);
  const withheld = assessments.filter((item) => item.status === "UNSUPPORTED" || item.status === "CONTRADICTED").length;
  const answer = released.length
    ? `${released.join(" ")}${withheld ? "\n\nSome generated claims were withheld because ClaimLock could not verify them." : ""}`
    : "I don't have enough verified evidence to answer that reliably.";
  return { answer, claims: assessments, released: released.length, withheld };
}

/** Sentence splitter that keeps abbreviations/decimals intact and re-attaches orphan citations. */
export function statements(text) {
  let working = String(text ?? "").trim();
  const placeholders = [];
  for (const abbr of abbreviations) {
    const pattern = new RegExp(`\\b${abbr.replace(/\./g, "\\.")}`, "gi");
    working = working.replace(pattern, (match) => {
      placeholders.push(match);
      return `\u0000${placeholders.length - 1}\u0000`;
    });
  }
  const raw = working
    .split(/(?<=[.!?])\s+|\n+/)
    .map((item) => item.replace(/\u0000(\d+)\u0000/g, (_, index) => placeholders[Number(index)]).trim())
    .map((item) => item.replace(/^[-*•]\s+/, "").trim())
    .filter(Boolean);

  const merged = [];
  for (const item of raw) {
    const citationOnly = /^(\[S\d+]\s*[,;]?\s*)+[.!?]?$/.test(item);
    if (citationOnly && merged.length) {
      merged[merged.length - 1] = `${merged[merged.length - 1]} ${item}`;
    } else {
      merged.push(item);
    }
  }
  return merged;
}

function bestSentences(claimTokens, source) {
  let best = { texts: [source], score: 0 };
  for (const sentence of statements(source)) {
    const score = overlap(claimTokens, sentence);
    if (score > best.score) best = { texts: [sentence], score };
    else if (score === best.score && score > 0) best.texts.push(sentence);
  }
  return best;
}

function overlap(claimTokens, source) {
  if (!claimTokens.size) return 0;
  const sourceTokens = tokenSet(source);
  let matches = 0;
  for (const token of claimTokens) if (sourceTokens.has(token)) matches += 1;
  return matches / claimTokens.size;
}

function tokenSet(value) {
  const normalized = String(value).toLowerCase().replace(/(?<=\d),(?=\d{3}\b)/g, "");
  return new Set((normalized.match(tokenRegex) ?? []).filter((token) => !stop.has(token)));
}

function numbersIn(value) {
  return new Set((String(value).match(numberRegex) ?? []).map((item) => item.replace(/,/g, "")));
}

function containsAll(haystack, needles) {
  for (const item of needles) if (!haystack.has(item)) return false;
  return true;
}

function round(value) {
  return Math.round(value * 1000) / 1000;
}
