# HPG in GitHub Codespaces

A codespace sets itself up: it installs Java 21, Docker and Node, builds every service, and starts the whole
stack with demo data. You only open it and wait.

## Create it (once)

1. On https://github.com/deepakgoli9640/docflow: **Code → Codespaces → ⋯ → New with options**.
2. Branch **master**, machine type **4-core** (16 GB; smaller machines are not offered), **Create codespace**.
3. Wait. The first time takes about **12–15 minutes**: setup builds everything once (`.devcontainer/post-create.sh`),
   then `.devcontainer/start.sh` starts HPG. The terminal ends with **"HPG is running."**

Every later start only runs `start.sh` (1–3 minutes): the build is kept.

## Open it

The **Ports** tab lists everything. Click the globe icon to open one in your browser:

| Port | What |
|---|---|
| 8090 | Dashboard |
| 8080 | API gateway |
| 8025 | Mailpit: the emails patients receive |
| 3000 | Grafana (only if you start the `obs` profile) |

Demo sign-in details (hospital API key, operations secret): `cat .devcontainer/.state/seed-output.txt`

## Postman

**Easiest, in the codespace terminal**: every request of the collection, with its checks:
```bash
./scripts/run-postman.sh
```
It should end with 128 checks, 0 failed. Watch the hospital's webhooks with `docker logs -f hook-receiver`.

**Inside VS Code**: the Postman extension is installed. Import `postman/HPG.postman_collection.json`, create an
environment with `baseUrl` = `http://localhost:8080` and `opsClientSecret` = the value after
`HPG_OPS_CLIENT_SECRET=` in `deploy/env/.env`.

**Postman desktop on your own computer**: forwarded ports are private (they need your GitHub login), so:
1. Ports tab → right-click **8080** → **Port Visibility → Public**, and copy its address.
2. In Postman: *Import → Link* →
   `https://raw.githubusercontent.com/deepakgoli9640/docflow/master/postman/HPG.postman_collection.json`
3. Environment: `baseUrl` = the port-8080 address (no trailing `/`), `opsClientId` = `ops-console`,
   `opsClientSecret` = the value from `deploy/env/.env`.
4. For the settlement upload (H1), download `samples/settlement/discrepancies.csv` from the codespace
   (right-click → Download) and pick it in the request's *Body → form-data → file*.

Anyone with a public port's address can call it: set it back to **Private** when you are done.

## Everyday commands

| Task | Command |
|---|---|
| Restart HPG | `bash .devcontainer/start.sh` |
| After `git pull` (new code) | `bash .devcontainer/start.sh --rebuild` |
| Service logs | `docker compose -f deploy/docker-compose.yml logs -f payment-service` |
| Fresh database | `docker compose -f deploy/docker-compose.yml --profile '*' down -v && rm -rf .devcontainer/.state && bash .devcontainer/start.sh` |
| Tests | `./mvnw verify` (uses Docker; takes a while on 4 cores) |

## Free hours

GitHub Free includes **120 core-hours a month**, so **30 hours** on this 4-core machine, plus 15 GB storage.
The spending limit is USD 0 by default, so you are not charged when it runs out.
- **Stop** the codespace when you finish (github.com/codespaces → ⋯ → Stop). It also stops after 30 minutes idle.
- **Delete** codespaces you no longer need: stopped ones still use storage.
