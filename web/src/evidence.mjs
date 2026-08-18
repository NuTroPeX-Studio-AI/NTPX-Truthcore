const patterns = [
  ["ignore_instructions", /\b(ignore|disregard|forget)\b.{0,40}\b(previous|prior|system|developer|instructions?)\b/i],
  ["system_prompt_request", /\b(system|developer)\s+prompt\b/i],
  ["role_override", /\b(you are now|act as|new role|override your role)\b/i],
  ["tool_override", /\b(call|execute|run|invoke)\b.{0,40}\b(tool|shell|command|function)\b/i],
  ["secret_exfiltration", /\b(reveal|print|show|exfiltrate)\b.{0,40}\b(secret|token|password|api key|hidden prompt)\b/i],
];

export function sanitizeEvidence(input = "") {
  const flags = new Set();
  const safeLines = String(input)
    .split(/\r?\n/)
    .filter((line) => {
      const matches = patterns.filter(([, regex]) => regex.test(line)).map(([name]) => name);
      matches.forEach((name) => flags.add(name));
      return matches.length === 0;
    });
  const text = safeLines.join("\n").trim();
  const quarantined = flags.size >= 2 || (!text && flags.size > 0);
  return { text, flags: [...flags], quarantined };
}

export function effectiveStrength(evidence, now = Date.now()) {
  if (!evidence || evidence.quarantined) return 0;
  const trust = Math.max(0, Math.min(1, Number(evidence.trust ?? 1)));
  if (evidence.expiresAt) {
    const expiry = Date.parse(evidence.expiresAt);
    if (Number.isFinite(expiry) && expiry <= now) return 0;
  }
  return trust;
}
