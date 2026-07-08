# IM Search E2E Tests

JavaScript end-to-end tests for `urn:xmpp:im:search:0` against the Docker Compose stack.

## Quick start

```bash
./e2e/run.sh
```

This starts OpenFire + OpenSearch (on non-default host ports to avoid clashes with other local XMPP servers) and runs 19 search scenarios: disco, capabilities, free-text, modifiers, pagination, inactive-room authz, indexing regressions, errors, and authorization.

## Manual run (stack already up)

Host ports are defined in the repo root `.env` (loaded automatically by Docker Compose). Use the same values when starting the stack and running tests:

```bash
docker compose up -d --build
./e2e/run.sh --no-up
```

Or without `run.sh`:

```bash
cd e2e
SKIP_DOCKER_UP=1 \
XMPP_PORT=15222 \
ADMIN_URL=http://127.0.0.1:19090 \
npm test
```

## Troubleshooting

### `docker compose up` stuck on "Waiting" for OpenSearch

The healthcheck probes `/_cluster/health` until the cluster is yellow/green. Compose can wait up to ~8 minutes (`start_period` 120s + 20 retries × 15s) before giving up.

If it never passes:

1. Check whether OpenSearch is actually serving: `curl http://127.0.0.1:9200/_cluster/health`
2. Inspect logs: `docker logs openfire-monitoring-plugin-opensearch-1`
3. **Stale/corrupt data volume** (e.g. after switching OpenSearch image versions): reset volumes and start clean:
   ```bash
   docker compose down -v
   docker compose up -d --build
   ```
   This deletes archived index data in the Docker volumes.
   If logs show `indexCreatedVersionMajor is in the future`, the volume was written by a newer OpenSearch (e.g. 3.x) than the image in `docker-compose.yml` (2.19.6) — a volume reset is required.

### `self-signed certificate` XMPP errors

OpenFire negotiates STARTTLS with a self-signed cert. Tests set `NODE_TLS_REJECT_UNAUTHORIZED=0` automatically via `npm test` / `run.sh`.

### Tests hang with no output, or fail after ~5 minutes

Usually the `before()` hook is waiting on a service that is not reachable. `run.sh` now fails fast if admin/OpenSearch are down; during setup you should see lines like `waiting for OpenFire admin login`.

Common causes:

1. **Port mismatch** — stack started on default ports (`9090`/`5222`) but tests expect e2e ports (`19090`/`15222`). Recreate the stack so it picks up `.env`: `docker compose down && docker compose up -d --build`, then `./e2e/run.sh --no-up`.
2. **OpenFire not reachable** on `XMPP_PORT` (wrong port, or another container bound to 5222/9090).
2. **OpenSearch unhealthy** — OpenFire will not start (`depends_on: service_healthy`). Check `docker compose ps` and `docker logs openfire-monitoring-plugin-opensearch-1`. If you switched OpenSearch major versions, reset the data volume: `docker compose down -v` (destroys archived data).
3. **Search feature not advertised** — persisted `openfire.xml` was missing `<host>opensearch</host>`. The entrypoint now injects OpenSearch settings on startup when absent.

### Tests fail after changing Java plugin code

`./e2e/run.sh --no-up` does not rebuild the OpenFire image. Rebuild and restart after plugin changes:

```bash
docker compose build openfire && docker compose up -d openfire
# wait ~90s for OpenFire to become healthy, then:
./e2e/run.sh --no-up
```

Or run `./e2e/run.sh` (without `--no-up`) to build and start the stack in one step.

```bash
docker exec openfire-monitoring-plugin-openfire-1 grep opensearch /var/lib/openfire/conf/openfire.xml
```

You should see `<host>opensearch</host>`.

## Environment variables

| Variable | Default (e2e) | Description |
|----------|---------------|-------------|
| `OPENFIRE_XMPP_PORT` | `15222` | Host port for c2s |
| `OPENFIRE_ADMIN_PORT` | `19090` | Admin console |
| `XMPP_PORT` | same as above | Used by test clients |
| `ADMIN_URL` | `http://127.0.0.1:19090` | Admin API base |
| `SKIP_DOCKER_UP` | `0` | Set to `1` if stack is already running |
