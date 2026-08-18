# NTPX TruthCore Web

TruthCore Web is the browser/web-server sibling of the native Android app. It includes a public marketing site at `/`, an installable web app at `/app`, a zero-dependency Node server, browser voice controls when supported, and a JavaScript port of the deterministic ClaimLock/evidence boundary.

## Run locally

```bash
cd web
npm test
npm run check
TRUTHCORE_ALLOWED_PROVIDER_HOSTS=api.example.com npm start
```

Open `http://localhost:8787` for the website or `http://localhost:8787/app` for the app.

## Model proxy security

The web server does **not** proxy arbitrary provider URLs by default. Provider hosts must be listed in `TRUTHCORE_ALLOWED_PROVIDER_HOSTS` as a comma-separated set of hostnames. Example:

```bash
TRUTHCORE_ALLOWED_PROVIDER_HOSTS=api.example.com,another-provider.example npm start
```

`*` is supported only as an explicit operator override and is not recommended for a public deployment. The server blocks cleartext URLs, embedded URL credentials, localhost/local-network targets, common metadata targets, and provider hosts resolving to private/reserved IP addresses.

The browser keeps the BYOK API key only in the current tab's JavaScript memory. It is not written to localStorage, cookies, IndexedDB, the service worker cache, or the GitHub repository.

## Truth boundary

Creative/transformation requests may be returned as `GENERATED`. Factual model drafts remain untrusted and pass through ClaimLock against the evidence packet. Unsupported factual output is withheld as `ABSTAINED`.

## Deployment shape

The static website and app shell can be served by any web host, but the full model-connected app needs the Node server (or an equivalent serverless adapter) because provider credentials should not be embedded into a public static bundle.
