import { verifyClaims } from "./claimlock.mjs";

export const WEB_VERSION = "0.5.3-alpha01";

export const localEvidence = [
  {
    id: "web-architecture",
    label: "TruthCore architecture",
    content: "TruthCore uses ClaimLock to withhold unsupported factual claims. ClaimLock is active in TruthCore Web.",
    trust: 1,
  },
  {
    id: "web-capabilities",
    label: "TruthCore Web capabilities",
    content: "TruthCore Web v0.5.3 includes a responsive public website, an installable web app shell, text chat, optional browser voice controls, local ClaimLock verification, and a server-side HTTPS model provider proxy.",
    trust: 1,
  },
  {
    id: "web-provider-security",
    label: "TruthCore Web provider security",
    content: "TruthCore Web v0.5.3 treats model providers as untrusted, accepts HTTPS provider endpoints only, keeps BYOK credentials out of persistent browser storage, and routes factual model drafts through ClaimLock before release.",
    trust: 1,
  },
];

export async function respond(input, { provider = null } = {}) {
  const request = String(input ?? "").trim();
  if (!request) return { text: "Enter or speak a request first.", verified: true, status: "LOCAL" };

  const local = localReply(request, Boolean(provider));
  if (local) return local;

  if (!provider) {
    return {
      text: "No model provider is connected for this request. Open Model settings to connect an allowed HTTPS chat endpoint. I won't invent an answer.",
      verified: false,
      status: "ABSTAINED",
    };
  }

  return isGenerativeRequest(request)
    ? generateNonFactual(request, provider)
    : generateEvidenceBound(request, provider);
}

function localReply(request, providerConnected) {
  const lower = request.toLowerCase();
  if (["hi", "hello", "hey", "hey truthcore"].includes(lower)) {
    return {
      text: `I'm here. TruthCore's truth gate is active${providerConnected ? " and a model provider is connected" : ""}.`,
      verified: true,
      status: "LOCAL",
    };
  }

  let draft = null;
  if (lower.includes("claimlock")) {
    draft = "TruthCore uses ClaimLock to withhold unsupported factual claims [S1].";
  } else if (lower.includes("what can you do") || lower === "help") {
    draft = "TruthCore Web v0.5.3 includes a responsive public website, an installable web app shell, text chat, optional browser voice controls, local ClaimLock verification, and a server-side HTTPS model provider proxy [S2].";
  } else if (lower.includes("model") || lower.includes("provider") || lower.includes("online")) {
    draft = "TruthCore Web v0.5.3 treats model providers as untrusted, accepts HTTPS provider endpoints only, keeps BYOK credentials out of persistent browser storage, and routes factual model drafts through ClaimLock before release [S3].";
  } else if (lower.includes("status")) {
    draft = "ClaimLock is active in TruthCore Web [S1]. TruthCore Web v0.5.3 includes an installable web app shell and a server-side HTTPS model provider proxy [S2].";
  }

  return draft ? releaseVerifiedDraft(draft) : null;
}

async function generateNonFactual(request, provider) {
  const result = await provider({
    systemPrompt: [
      "You are the reasoning model inside NTPX TruthCore.",
      "This request has been deterministically classified as creative, transformative, drafting, translation, summarization, or ideation work.",
      "Produce the requested artifact directly.",
      "Do not add external factual claims, statistics, dates, citations, or claims of real-world verification unless the user supplied them.",
      "Never claim the output was verified by TruthCore.",
    ].join(" "),
    userPrompt: request,
    temperature: 0.5,
  });
  if (!result?.success) return providerFailure(result?.error);
  return { text: result.text, verified: false, status: "GENERATED" };
}

async function generateEvidenceBound(request, provider) {
  const evidencePacket = localEvidence.map((item, index) => `[S${index + 1}] ${item.content}`).join("\n");
  const result = await provider({
    systemPrompt: [
      "You are the reasoning model inside NTPX TruthCore. You are not the authority layer.",
      "Answer factual requests only from the supplied evidence packet.",
      "Every factual sentence must cite one or more supplied source IDs exactly like [S1].",
      "Never cite a source that does not directly support the sentence.",
      "If the evidence packet does not support the requested fact, reply exactly: UNKNOWN: I do not have verified evidence for that request.",
      "Do not use pretrained knowledge to fill evidence gaps.",
    ].join(" "),
    userPrompt: `User request:\n${request}\n\nEvidence packet:\n${evidencePacket}`,
    temperature: 0,
  });
  if (!result?.success) return providerFailure(result?.error);
  return releaseVerifiedDraft(result.text);
}

function releaseVerifiedDraft(draft) {
  const locked = verifyClaims(draft, localEvidence);
  const onlySupportedFacts = locked.claims.length > 0 && locked.claims.every((claim) => claim.status === "SUPPORTED");
  const hasUnknown = locked.claims.some((claim) => claim.status === "UNKNOWN");
  const text = hasUnknown ? locked.answer.replace(/^UNKNOWN:\s*/i, "") : locked.answer;
  return {
    text,
    verified: onlySupportedFacts && locked.withheld === 0,
    status: onlySupportedFacts && locked.withheld === 0 ? "VERIFIED" : "ABSTAINED",
  };
}

function providerFailure(error) {
  return {
    text: error ? `Model provider error: ${error}` : "Model provider request failed.",
    verified: false,
    status: "PROVIDER_ERROR",
  };
}

function isGenerativeRequest(request) {
  const normalized = request.trim().toLowerCase();
  const prefixes = [
    "write ", "draft ", "rewrite ", "brainstorm ", "create ", "compose ", "outline ",
    "generate ", "make ", "translate ", "summarize ", "roleplay ", "imagine ",
    "help me write ", "help me draft ", "help me phrase ",
  ];
  return prefixes.some((prefix) => normalized.startsWith(prefix));
}
