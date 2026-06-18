# Reverse proxy in front of `honcho-inspector-backend`

> **Audience:** operators deploying the backend to a server reachable from
> the public internet (or a corporate LAN).
> **Status:** active — every production install should be behind a reverse
> proxy.
> **Companion doc:** [`docs/SECURITY.md`](SECURITY.md) — read §1.4 and §5
> of that doc first if you haven't.

---

## 1. Why a reverse proxy is required

The Spring Boot backend listens on plain HTTP (default `0.0.0.0:8080`).
There is no built-in TLS, no built-in HSTS, no built-in rate limiting, and
no built-in security headers. That is by design — the proxy is the right
place for all of those.

| Concern | Where it lives |
| --- | --- |
| TLS termination (HTTPS) | **Reverse proxy** |
| ACME / Let's Encrypt / cert renewal | **Reverse proxy** (nginx in this deployment) |
| HTTP → HTTPS redirect | **Reverse proxy** |
| HSTS, modern cipher suites, ALPN | **Reverse proxy** |
| `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, `CSP` | **Reverse proxy** |
| Per-IP request rate limiting | **Reverse proxy** |
| Static assets for the Angular UI | **Reverse proxy** (serves `dist/honcho-inspector-ui/`) |
| JSON API (`/api/**`) | Reverse proxy → backend (loopback) |

The backend itself should **not** be reachable from outside the host in
production. Bind it to `127.0.0.1` (or a unix socket) and let the proxy
be the only public listener.

---

## 2. Shared requirements (apply to every proxy)

These are non-negotiable. The example configs below implement them; if
you hand-roll a different proxy, you must enforce the same.

### 2.1 Transport

- **TLS 1.2 minimum, TLS 1.3 preferred.** Disable SSLv3, TLS 1.0, TLS 1.1.
- **HTTP/2** enabled on the public listener.
- **HTTP/3 (QUIC)** is optional but a nice-to-have if your proxy supports it.
- A **modern cipher list** that prefers AEAD ciphers (`TLS_AES_256_GCM_SHA384`,
  `TLS_AES_128_GCM_SHA256`, `TLS_CHACHA20_POLY1305_SHA256`) and uses ECDHE
  for forward secrecy.
- **OCSP stapling** on.
- **HSTS** with `max-age=63072000; includeSubDomains; preload` (two years,
  includes subdomains, preload-eligible).
- **HTTP → HTTPS** redirect on port 80. Never serve mixed content.

### 2.2 Security headers (add to every response)

```
Strict-Transport-Security: max-age=63072000; includeSubDomains; preload
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Permissions-Policy: ()
Content-Security-Policy: default-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Resource-Policy: same-origin
```

> The CSP is strict because the UI is a small Angular SPA and the backend
> serves only JSON; `connect-src 'self'` covers the `/api/**` calls the
> SPA makes. Loosen only if you embed a third-party widget.

### 2.3 Network topology

```
browser
   │  (public internet, TLS 1.3, port 443)
   ▼
┌──────────────────────────┐
│  Reverse proxy           │  ← binds public NIC, port 443
│  (nginx / Apache / Caddy)│     manages its own certs
└──────────┬───────────────┘
           │  (loopback, plain HTTP, port 8080)
           ▼
┌──────────────────────────┐
│  honcho-inspector        │  ← binds 127.0.0.1:8080
│  Spring Boot backend     │
└──────────────────────────┘
```

- The backend listens on `127.0.0.1` only:
  `bin/honcho-inspector` or `--server.address=127.0.0.1` via
  `JAVA_OPTS`.
- The proxy uses its loopback interface to reach the backend.
- If you must use a non-loopback private IP, **firewall it** so only the
  proxy can reach it.

### 2.4 Backend CORS interaction

When the proxy serves the UI from the same origin as the API
(`https://inspector.example.com/api/...`), the browser sees same-origin
requests and **CORS is irrelevant**. The production CORS list should
still be set (defence-in-depth, in case the UI is ever served from a
different origin) but it does not need to include the proxy's public
origin unless the API is also called cross-origin.

`CORS_ALLOWED_ORIGINS` is set in
`/etc/honcho-inspector/application.yml` — see
[`README.md`](../README.md) for the full env-var table.

---

## 3. nginx (primary — certs managed by nginx)

This deployment assumes **nginx manages the HTTPS certificates** itself —
typically via `certbot --nginx` (Let's Encrypt). The backend is not
involved in cert issuance, renewal, or storage.

### 3.1 Site config

Save as `/etc/nginx/sites-available/honcho-inspector.conf`, then
`ln -s ../sites-available/honcho-inspector.conf /etc/nginx/sites-enabled/`
and `nginx -t && systemctl reload nginx`.

```nginx
# honcho-inspector: TLS-terminating reverse proxy.
# Certs are managed by nginx (certbot --nginx). Backend listens on 127.0.0.1:8080.

# Rate-limit zone shared across all server blocks. 10 req/s per IP, burst 20.
# 10 req/s is generous for a small admin surface; tighten if you see abuse.
limit_req_zone $binary_remote_addr zone=hi_limit:10m rate=10r/s;
limit_req_status 429;

# Upstream — backend, loopback only.
upstream honcho_inspector_backend {
    server 127.0.0.1:8080 fail_timeout=5s max_fails=3;
    keepalive 16;
}

# Plain HTTP: 301 to HTTPS. ACME http-01 challenge is allowed through.
server {
    listen 80;
    listen [::]:80;
    server_name inspector.example.com;

    # Let certbot's http-01 challenge reach the challenge dir.
    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    # Everything else: permanent redirect to HTTPS.
    location / {
        return 301 https://$host$request_uri;
    }
}

# HTTPS.
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name inspector.example.com;

    # --- Certificates (managed by certbot / nginx, not the backend) ----
    ssl_certificate     /etc/letsencrypt/live/inspector.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/inspector.example.com/privkey.pem;
    ssl_trusted_certificate /etc/letsencrypt/live/inspector.example.com/chain.pem;

    # --- Modern TLS only -----------------------------------------------
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;          # TLS 1.3 ciphers are server-preferred
                                           # automatically; this lets the client
                                           # choose within TLS 1.2.
    ssl_ciphers ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256;
    ssl_ecdh_curve X25519:secp384r1;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;
    ssl_session_tickets off;
    ssl_stapling on;
    ssl_stapling_verify on;
    resolver 1.1.1.1 8.8.8.8 valid=300s;
    resolver_timeout 5s;

    # --- Security headers (apply to every response) --------------------
    add_header Strict-Transport-Security "max-age=63072000; includeSubDomains; preload" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "no-referrer" always;
    add_header Permissions-Policy "()" always;
    add_header Content-Security-Policy "default-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'" always;
    add_header Cross-Origin-Opener-Policy "same-origin" always;
    add_header Cross-Origin-Resource-Policy "same-origin" always;

    # Hide nginx version in Server: header.
    server_tokens off;

    # --- Body size cap (defence in depth) -----------------------------
    client_max_body_size 2m;

    # --- Logging ------------------------------------------------------
    access_log /var/log/nginx/honcho-inspector.access.log;
    error_log  /var/log/nginx/honcho-inspector.error.log warn;

    # --- Static Angular UI --------------------------------------------
    # Adjust this root to wherever the UI is built. The frontend repo
    # produces dist/honcho-inspector-ui/browser/ with index.html.
    root /var/www/honcho-inspector-ui;
    index index.html;

    # Long cache for fingerprinted assets, no cache for index.html.
    location ~* \.(?:css|js|woff2?|svg|png|jpg|jpeg|gif|ico)$ {
        try_files $uri =404;
        expires 1y;
        add_header Cache-Control "public, immutable";
        access_log off;
    }
    location = /index.html {
        add_header Cache-Control "no-store" always;
        try_files $uri =404;
    }

    # SPA fallback: any non-asset path falls through to index.html so the
    # Angular router can take over.
    location / {
        try_files $uri $uri/ /index.html;
    }

    # --- API: proxy to the backend ------------------------------------
    location /api/ {
        # Per-IP rate limit. Burst 20 covers the 5–6 endpoints the SPA
        # fires on initial load.
        limit_req zone=hi_limit burst=20 nodelay;
        limit_req_log_level warn;

        proxy_pass http://honcho_inspector_backend;

        # Timeouts
        proxy_connect_timeout 5s;
        proxy_send_timeout    30s;
        proxy_read_timeout    30s;

        # Keep the connection warm between the proxy and the backend.
        proxy_http_version 1.1;
        proxy_set_header Connection          "";
        proxy_set_header Host                $host;
        proxy_set_header X-Real-IP           $remote_addr;
        proxy_set_header X-Forwarded-For     $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto   $scheme;
        proxy_set_header X-Forwarded-Host    $host;
        proxy_set_header X-Forwarded-Port    $server_port;

        # The backend trusts these headers (server.forward-headers-strategy
        # = framework). Keep the backend bound to 127.0.0.1 so the
        # headers cannot be spoofed from outside.
        proxy_buffering on;
        proxy_request_buffering on;

        # Don't pass through the Host / X-Forwarded-* from the client
        # to the backend as-is — we already set them above.
    }

    # Health-check passthrough (no rate limit so monitors aren't throttled).
    location = /api/health {
        proxy_pass http://honcho_inspector_backend;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        access_log off;
    }
}
```

### 3.2 What each stanza does (annotated)

- **`limit_req_zone ... rate=10r/s`** — per-IP token-bucket rate limit.
  10 requests per second sustained, with a 20-request burst allowed. The
  backend itself has no rate limiter, so this is the operator's primary
  brute-force defence for `/api/auth/login` and `/api/auth/register`
  (see [`docs/SECURITY.md`](SECURITY.md) finding **F-01**).
- **`http2 on;`** — enables HTTP/2 on the same listen socket. Required for
  modern browsers' connection coalescing and is a free performance win.
- **`ssl_protocols TLSv1.2 TLSv1.3;`** — disables everything older.
- **`ssl_prefer_server_ciphers off;`** — for TLS 1.3 the server's
  preference is overridden by the spec; for TLS 1.2, the client chooses.
  Modern browsers all prefer the AEAD ciphers we list. If you need to
  support very old clients, flip this to `on` and order the cipher list
  with the strongest first.
- **`ssl_ecdh_curve X25519:secp384r1;`** — keeps the ECDHE curve list
  short and modern. Avoids SWEET32-style downgrade attacks.
- **`add_header ... always;`** — the `always` flag is critical: it makes
  nginx attach the header on error responses (4xx/5xx) too, not just on
  2xx/3xx.
- **`client_max_body_size 2m;`** — the backend has no per-field size cap
  on the API-key DTO (finding **F-07**), so the proxy enforces a
  conservative 2 MB cap globally.
- **`proxy_set_header X-Forwarded-Proto $scheme;`** — needed because the
  backend sets `server.forward-headers-strategy: framework`; the redirect
  / link generation in any future code will then produce HTTPS URLs.
- **SPA fallback `try_files $uri $uri/ /index.html;`** — keeps the UI's
  client-side router working under any path. Adjust the root to where
  the UI was built (Angular's default is `dist/<project>/browser/`).
- **The `= /api/health` block** bypasses the rate limit so external
  monitoring (Pingdom, Uptime Kuma, etc.) doesn't trip it.

### 3.3 Initial cert issuance (certbot)

The certs are managed **by nginx**, not by the backend. The standard
tooling is `certbot` from Let's Encrypt.

```bash
# 1. Install certbot and the nginx plugin.
apt install certbot python3-certbot-nginx        # Debian/Ubuntu
dnf install certbot python3-certbot-nginx        # Fedora/RHEL

# 2. Open 80 and 443.
ufw allow 80/tcp
ufw allow 443/tcp

# 3. Issue + install. The plugin edits the nginx config and reloads nginx.
certbot --nginx -d inspector.example.com

# 4. Verify renewal.
certbot renew --dry-run

# 5. (Optional) A timer / cron is installed automatically by the package;
#    no further action needed.
systemctl list-timers | grep certbot
```

If you cannot use the nginx plugin (e.g. the server runs behind a
firewall that can't be opened for http-01), use the `webroot` or
`dns-<provider>` plugins instead. Certs still end up at
`/etc/letsencrypt/live/<domain>/`, and the proxy reloads on renewal via
`--deploy-hook "systemctl reload nginx"`.

---

## 4. Apache (alternative)

Use Apache if you must — for example, on a host that already has Apache
as the primary web server. Certs are still managed by Apache (via
`certbot --apache`).

### 4.1 Modules

```bash
a2enmod ssl http2 headers proxy proxy_http rewrite remoteip
systemctl restart apache2
```

### 4.2 Site config

Save as `/etc/apache2/sites-available/honcho-inspector.conf`, then
`a2ensite honcho-inspector && systemctl reload apache2`.

```apache
# honcho-inspector: TLS-terminating reverse proxy (Apache).
# Certs are managed by certbot --apache. Backend listens on 127.0.0.1:8080.

<VirtualHost *:80>
    ServerName inspector.example.com

    # ACME http-01 challenge passthrough (certbot installs this for you).
    Alias /.well-known/acme-challenge/ /var/www/html/.well-known/acme-challenge/
    <Directory "/var/www/html/.well-known/acme-challenge/">
        Require all granted
    </Directory>

    # Everything else: permanent redirect to HTTPS.
    RewriteEngine On
    RewriteRule ^(.*)$ https://%{HTTP_HOST}$1 [R=301,L]
</VirtualHost>

<VirtualHost *:443>
    ServerName inspector.example.com

    # --- TLS (cert paths managed by certbot) --------------------------
    SSLEngine on
    Protocols h2 http/1.1
    SSLProtocol all -SSLv3 -TLSv1 -TLSv1.1
    SSLCipherSuite ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256
    SSLHonorCipherOrder off
    SSLSessionCache shmcb:/var/run/apache2/ssl_scache(512000)
    SSLSessionTimeout 86400
    SSLOpenSSLConfCmd ECDHParameters secp384r1:X25519
    SSLStaplingCache shmcb:/var/run/apache2/stapling_cache(128000)

    # These three are inserted/managed by `certbot --apache`; shown here
    # for reference. Don't hand-edit; re-run certbot to renew.
    # SSLCertificateFile      /etc/letsencrypt/live/inspector.example.com/fullchain.pem
    # SSLCertificateKeyFile   /etc/letsencrypt/live/inspector.example.com/privkey.pem
    # SSLCertificateChainFile /etc/letsencrypt/live/inspector.example.com/chain.pem

    Header always set Strict-Transport-Security "max-age=63072000; includeSubDomains; preload"
    Header always set X-Content-Type-Options "nosniff"
    Header always set Referrer-Policy "no-referrer"
    Header always set Permissions-Policy "()"
    Header always set Content-Security-Policy "default-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"
    Header always set Cross-Origin-Opener-Policy "same-origin"
    Header always set Cross-Origin-Resource-Policy "same-origin"

    ServerTokens Prod
    TraceEnable off

    # --- Body size cap ------------------------------------------------
    LimitRequestBody 2097152

    # --- Logging ------------------------------------------------------
    ErrorLog  /var/log/apache2/honcho-inspector.error.log
    CustomLog /var/log/apache2/honcho-inspector.access.log combined

    # --- Remote IP (so the backend sees the real client IP) -----------
    RemoteIPHeader X-Forwarded-For
    RemoteIPInternalProxy 127.0.0.1/8

    # --- Static UI ----------------------------------------------------
    DocumentRoot /var/www/honcho-inspector-ui
    <Directory /var/www/honcho-inspector-ui>
        Options -Indexes +FollowSymLinks
        AllowOverride None
        Require all granted
    </Directory>

    # Fingerprinted assets: long cache.
    <FilesMatch "\.(?:css|js|woff2?|svg|png|jpg|jpeg|gif|ico)$">
        Header set Cache-Control "public, max-age=31536000, immutable"
    </FilesMatch>

    # index.html: no cache.
    <Files "index.html">
        Header set Cache-Control "no-store"
    </Files>

    # SPA fallback — let index.html handle unknown paths.
    RewriteEngine On
    RewriteCond %{REQUEST_FILENAME} !-f
    RewriteCond %{REQUEST_FILENAME} !-d
    RewriteRule ^ /index.html [L]

    # --- Rate limiting -----------------------------------------------
    # 10 req/s per IP for the API. Burst 20.
    <IfModule mod_ratelimit.c>
        <Location /api/>
            SetOutputFilter RATE_LIMIT
            SetEnv rate-limit 200
        </Location>
    </IfModule>

    # --- Proxy to the backend ----------------------------------------
    ProxyPreserveHost On
    ProxyPass        /api/ http://127.0.0.1:8080/api/ retry=0
    ProxyPassReverse /api/ http://127.0.0.1:8080/api/

    # Headers forwarded to the backend.
    <Location /api/>
        RequestHeader set X-Forwarded-Proto   "https"
        RequestHeader set X-Forwarded-Port    "443"
        RequestHeader set X-Forwarded-Host    "%{HTTP_HOST}s"
        RequestHeader set X-Real-IP           "%{REMOTE_ADDR}s"
    </Location>

    # Health-check bypass.
    <Location /api/health>
        ProxyPass        http://127.0.0.1:8080/api/health
        ProxyPassReverse http://127.0.0.1:8080/api/health
        Require all granted
    </Location>
</VirtualHost>
```

### 4.3 Initial cert issuance (certbot)

```bash
apt install certbot python3-certbot-apache
certbot --apache -d inspector.example.com
certbot renew --dry-run
```

`certbot --apache` edits the `<VirtualHost *:443>` block to insert the
`SSLCertificate*` directives. Do not move the certs by hand.

---

## 5. Caddy (alternative — automatic cert management)

Caddy is the easiest option if you want cert management to be invisible.
Caddy's built-in ACME client obtains and renews Let's Encrypt
certificates automatically the first time it serves a domain — no
`certbot`, no cron, no plugins.

> Caddy will request and renew certs for **every public hostname** in
> its config. If your server has multiple public sites, you may prefer
> nginx with `certbot --nginx` so each cert's renewal is explicit. Caddy
> is a great choice for a single-tenant install like this one.

### 5.1 Install

```bash
# Debian / Ubuntu
apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
    | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
    | tee /etc/apt/sources.list.d/caddy-stable.list
apt update && apt install caddy
```

### 5.2 Caddyfile

Save as `/etc/caddy/Caddyfile`.

```caddyfile
# honcho-inspector: TLS-terminating reverse proxy (Caddy).
# Caddy auto-manages Let's Encrypt certs — no certbot required.
# Backend listens on 127.0.0.1:8080.

inspector.example.com {

    # --- TLS, HSTS, HTTP/2, HTTP/3 (all automatic) -------------------
    # Caddy obtains and renews the cert from Let's Encrypt by default.
    # HSTS is opt-in: enable it explicitly with the header below.

    encode zstd gzip

    # --- Security headers (apply to every response) -------------------
    header {
        # HSTS preload-eligible, 2 years, includes subdomains.
        Strict-Transport-Security "max-age=63072000; includeSubDomains; preload"

        # Belt-and-braces for the API.
        X-Content-Type-Options "nosniff"
        Referrer-Policy "no-referrer"
        Permissions-Policy "()"
        Content-Security-Policy "default-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"
        Cross-Origin-Opener-Policy "same-origin"
        Cross-Origin-Resource-Policy "same-origin"

        # Don't reveal Caddy version.
        -Server
    }

    # --- Body size cap (defence in depth) -----------------------------
    request_body {
        max_size 2MB
    }

    # --- Rate limiting (per IP, sliding window) -----------------------
    # 100 req / 30s per client IP for the API.
    @api path /api/*
    rate_limit @api 100r/30s

    # --- Static UI ----------------------------------------------------
    root * /var/www/honcho-inspector-ui

    @assets {
        path *.css *.js *.woff *.woff2 *.svg *.png *.jpg *.jpeg *.gif *.ico
    }
    header @assets Cache-Control "public, max-age=31536000, immutable"

    @index path /index.html
    header @index Cache-Control "no-store"

    # SPA fallback — let the Angular router handle unknown paths.
    try_files {path} {path}/ /index.html

    # --- API: proxy to the backend ------------------------------------
    reverse_proxy 127.0.0.1:8080 {
        header_up Host {host}
        header_up X-Real-IP {remote_host}
        header_up X-Forwarded-For {remote_host}
        header_up X-Forwarded-Proto {scheme}
        header_up X-Forwarded-Host {host}
        header_up X-Forwarded-Port {server_port}

        # Health-check endpoint.
        @health path /api/health
        # (no rate limit on /api/health is implicit — the matcher above
        #  applies to /api/*; the @api rate limit excludes /api/health
        #  only if you factor it out. Adjust if your monitors trip it.)

        transport http {
            dial_timeout 5s
            response_header_timeout 30s
        }
    }

    # --- Logging ------------------------------------------------------
    log {
        output file /var/log/caddy/honcho-inspector.log {
            roll_size 100mb
            roll_keep 14
        }
    }
}
```

Then:

```bash
# Validate and run.
caddy validate --config /etc/caddy/Caddyfile
systemctl enable --now caddy

# Caddy will request a Let's Encrypt cert on first request and serve
# HTTPS from then on. Confirm with:
curl -I https://inspector.example.com/api/health
```

---

## 6. Operational checklist (all three proxies)

Before going live, verify the following. The commands assume the
deployment is on `https://inspector.example.com/`.

- [ ] `curl -I https://inspector.example.com/` returns `200` and
      `strict-transport-security: max-age=63072000; includeSubDomains; preload`.
- [ ] `curl -I http://inspector.example.com/` returns `301` to HTTPS.
- [ ] `curl -sI https://inspector.example.com/ | grep -i 'content-security-policy'`
      shows the strict CSP from §2.2.
- [ ] `curl -sI https://inspector.example.com/ | grep -i server` shows
      the proxy's own banner (`nginx`, `Apache`, `Caddy`) — **not**
      Spring, Tomcat, or a framework version.
- [ ] `curl -sI https://inspector.example.com/api/health` returns
      `200 {"ok":true,...}` with the security headers still attached.
- [ ] The backend port (8080) is **not** reachable from outside the
      host: `curl -v http://inspector.example.com:8080/api/health` from
      a separate machine times out / is refused.
- [ ] The cert is valid and was issued within the last 90 days:
      `echo | openssl s_client -servername inspector.example.com -connect inspector.example.com:443 2>/dev/null | openssl x509 -noout -dates -subject -issuer`.
- [ ] The TLS handshake offers TLS 1.2 and 1.3 and nothing older:
      `nmap --script ssl-enum-ciphers -p 443 inspector.example.com`.
- [ ] A burst of 50 requests in 1 second from one IP returns
      `429` for some of them (rate limit is engaged):
      `for i in $(seq 1 50); do curl -s -o /dev/null -w "%{http_code}\n" https://inspector.example.com/api/health; done | sort | uniq -c`.
- [ ] `HONCHO_CRYPTO_KEY` is set in the backend's environment
      (`/proc/<pid>/environ` is unreadable to other users, or the
      service is run under a dedicated user with the key in a
      systemd `LoadCredential=`).

---

## 7. Reference: which headers go where

| Header | Where set | Notes |
| --- | --- | --- |
| `Strict-Transport-Security` | **Proxy** (always `always`) | HSTS in the app is meaningless without TLS; the proxy is the right layer. |
| `X-Content-Type-Options` | **Proxy** (or app) | `always` so error responses get it too. |
| `Referrer-Policy` | **Proxy** | Default `no-referrer` for a JSON API + SPA. |
| `Permissions-Policy` | **Proxy** | Empty allowlist closes every feature. |
| `Content-Security-Policy` | **Proxy** | Strict; loosen only if the UI embeds third-party widgets. |
| `Cross-Origin-*-Policy` | **Proxy** | Same-origin for the SPA. |
| `X-Frame-Options` | **Proxy** | Covered by `frame-ancestors 'none'` in CSP; older browsers may also need `DENY`. |
| `X-Session-Id` (response, not a security header) | **Backend** | Exposed via CORS `exposedHeaders` so the SPA can read it cross-origin. |

If you ever set the same header in both layers, the **proxy wins** (the
proxy is the last to write). The app currently sets none of the
above — see [`docs/SECURITY.md`](SECURITY.md) finding **F-03** for the
app-side hardening that would close the loop if the proxy is misconfigured.
