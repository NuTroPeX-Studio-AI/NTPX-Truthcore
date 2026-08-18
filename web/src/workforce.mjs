import { sanitizeEvidence } from "./evidence.mjs";

export async function runWorkforce(goal, provider = null) {
  const request = String(goal ?? "").trim();
  if (!request) return { ok: false, status: "ABSTAINED", error: "A team goal is required" };
  if (typeof provider !== "function") return { ok: false, status: "ABSTAINED", error: "A model provider is required for the review team" };

  const planner = await ask(
    provider,
    "You are the planning specialist in NTPX TruthCore. Produce a concise proposed plan. Do not claim actions ran. Do not invent facts. Mark assumptions explicitly.",
    request,
  );
  if (!planner) return { ok: false, status: "PROVIDER_ERROR", error: "Planning specialist failed" };

  const safePlanner = sanitize(planner);
  const critic = await ask(
    provider,
    "You are the critic specialist in NTPX TruthCore. Review only the supplied proposed plan. Identify unsupported assumptions, missing dependencies, risks, and ambiguity. Do not add outside facts.",
    `Goal:\n${request}\n\nProposed plan:\n${safePlanner}`,
  );
  if (!critic) return { ok: false, status: "PROVIDER_ERROR", error: "Critic specialist failed", stages: [safePlanner] };

  const safeCritic = sanitize(critic);
  const reviewer = await ask(
    provider,
    "You are the review specialist in NTPX TruthCore. Synthesize the goal, proposed plan, and critique into a bounded recommendation. Do not claim any action was executed. Separate assumptions from recommendations.",
    `Goal:\n${request}\n\nPlan:\n${safePlanner}\n\nCritique:\n${safeCritic}`,
  );
  if (!reviewer) return { ok: false, status: "PROVIDER_ERROR", error: "Review specialist failed", stages: [safePlanner, safeCritic] };

  const safeReviewer = sanitize(reviewer);
  return {
    ok: true,
    status: "TEAM_GENERATED",
    text: safeReviewer,
    stages: [safePlanner, safeCritic, safeReviewer],
  };
}

async function ask(provider, systemPrompt, userPrompt) {
  const result = await provider({ systemPrompt, userPrompt, temperature: 0.1 });
  return result?.success && result.text ? String(result.text) : null;
}

function sanitize(value) {
  const result = sanitizeEvidence(String(value).slice(0, 12000));
  return result.quarantined ? "[specialist output quarantined]" : result.text;
}
