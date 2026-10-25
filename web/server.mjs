import path from "node:path";
import { fileURLToPath } from "node:url";
import { createTruthCoreServer } from "./src/http.mjs";
import { parseAllowedHosts } from "./src/provider.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT || 8787);
const host = process.env.HOST || "0.0.0.0";
const allowedHosts = parseAllowedHosts();

const server = createTruthCoreServer({ publicDir: path.join(here, "public"), allowedHosts });

server.listen(port, host, () => {
  console.log(`NTPX TruthCore Web listening on http://${host === "0.0.0.0" ? "127.0.0.1" : host}:${port}`);
  if (!allowedHosts.size) console.log("Model proxy disabled until TRUTHCORE_ALLOWED_PROVIDER_HOSTS is configured.");
  if (allowedHosts.has("*")) console.warn("TRUTHCORE_ALLOWED_PROVIDER_HOSTS=* allows any public HTTPS host. Prefer an explicit allowlist in production.");
});

for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, () => {
    console.log(`Received ${signal}; closing TruthCore Web.`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 5000).unref();
  });
}
