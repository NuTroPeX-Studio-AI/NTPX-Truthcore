import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { readFile, stat } from "node:fs/promises";
import { respond } from "./src/conversation.mjs";
import { createProvider, parseAllowedHosts } from "./src/provider.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(here, "public");
const port = Number(process.env.PORT || 8787);
const allowedHosts = parseAllowedHosts();

const server = http.createServer(async (req, res) => {
  try {
    setSecurityHeaders(res);
    const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);

    if (req.method === "GET" && url.pathname === "/api/health") {
      return json(res, 200, { ok: true, service: "ntpx-truthcore-web", version: "0.5.3-alpha01" });
    }

    if (req.method === "POST" && url.pathname === "/api/provider/test") {
      const body = await readJson(req);
      const provider = createProvider(body?.provider ?? {}, { allowedHosts });
      const result = await provider({
        systemPrompt: "Return exactly READY.",
        userPrompt: "Connection test.",
        temperature: 0,
      });
      return json(res, result.success ? 200 : 400, result.success ? { ok: true } : { ok: false, error: result.error });
    }

    if (req.method === "POST" && url.pathname === "/api/chat") {
      const body = await readJson(req);
      const message = String(body?.message ?? "").slice(0, 12000);
      const config = body?.provider ?? null;
      const provider = config?.baseUrl && config?.model ? createProvider(config, { allowedHosts }) : null;
      const reply = await respond(message, { provider });
      return json(res, 200, reply);
    }

    if (req.method !== "GET" && req.method !== "HEAD") {
      return text(res, 405, "Method not allowed");
    }

    return serveStatic(url.pathname, req.method === "HEAD", res);
  } catch (error) {
    const status = error?.statusCode || 500;
    if (status >= 500) console.error("TruthCore web request failed", error?.message || error);
    return json(res, status, { ok: false, error: status === 500 ? "Internal server error" : error.message });
  }
});

server.listen(port, () => {
  console.log(`NTPX TruthCore Web listening on http://127.0.0.1:${port}`);
  if (!allowedHosts.size) console.log("Model proxy disabled until TRUTHCORE_ALLOWED_PROVIDER_HOSTS is configured.");
});

async function readJson(req) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > 65536) {
      const error = new Error("Request body is too large");
      error.statusCode = 413;
      throw error;
    }
    chunks.push(chunk);
  }
  const raw = Buffer.concat(chunks).toString("utf8");
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch {
    const error = new Error("Invalid JSON request");
    error.statusCode = 400;
    throw error;
  }
}

async function serveStatic(pathname, headOnly, res) {
  const route = pathname === "/" ? "/index.html" : pathname === "/app" ? "/app.html" : pathname;
  const decoded = decodeURIComponent(route);
  const candidate = path.resolve(publicDir, `.${decoded}`);
  if (!candidate.startsWith(`${publicDir}${path.sep}`)) return text(res, 403, "Forbidden");

  let filePath = candidate;
  try {
    const info = await stat(filePath);
    if (info.isDirectory()) filePath = path.join(filePath, "index.html");
  } catch {
    return text(res, 404, "Not found");
  }

  const body = await readFile(filePath);
  res.statusCode = 200;
  res.setHeader("content-type", contentType(filePath));
  res.setHeader("cache-control", filePath.endsWith("sw.js") ? "no-cache" : "public, max-age=300");
  res.end(headOnly ? undefined : body);
}

function setSecurityHeaders(res) {
  res.setHeader("x-content-type-options", "nosniff");
  res.setHeader("referrer-policy", "no-referrer");
  res.setHeader("x-frame-options", "DENY");
  res.setHeader("permissions-policy", "camera=(), geolocation=(), payment=(), usb=()");
  res.setHeader("content-security-policy", "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'self'; manifest-src 'self'; worker-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
}

function json(res, status, value) {
  res.statusCode = status;
  res.setHeader("content-type", "application/json; charset=utf-8");
  res.setHeader("cache-control", "no-store");
  res.end(JSON.stringify(value));
}

function text(res, status, value) {
  res.statusCode = status;
  res.setHeader("content-type", "text/plain; charset=utf-8");
  res.end(value);
}

function contentType(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  return {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".svg": "image/svg+xml",
    ".webmanifest": "application/manifest+json; charset=utf-8",
    ".json": "application/json; charset=utf-8",
  }[ext] || "application/octet-stream";
}
