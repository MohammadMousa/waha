# Deployment settings (server)

Optional settings in the server's `.env` (next to `docker-compose.yml`). Unset keeps the old behavior, so a developer machine needs nothing.

| Setting | Default | On the server |
|---|---|---|
| `DB_PORT_BIND` | all interfaces | `127.0.0.1`: the database port is reachable from the server itself only. Use an SSH tunnel from a laptop. |
| `WAHA_PORT_BIND` | all interfaces | `127.0.0.1`: the backend port is not reachable from outside. Nginx on 443 stays the public entry, and the deploy health check uses localhost. |
| `DB_ROOT_PASSWORD` | `root` | A random value, used by the database and the backend. |

Port settings take effect when the containers are recreated (next deploy). The database data is not touched.

## Reaching the database from a laptop
```
ssh -i <key>.pem -L 3308:127.0.0.1:3307 ubuntu@<server>
```
Then connect to `localhost:3308`. Or on the server: `docker exec -it waha-mysql-1 mysql -uroot -p`.

## Changing the database password on a server that already has data
`DB_ROOT_PASSWORD` only sets the password when the database is first created. On an existing database it must be changed in MySQL **and** in `.env` together, otherwise the backend cannot log in.

1. Check the newest backup is fresh and valid. Keep the new password somewhere safe first.
2. Generate it: `openssl rand -hex 24` (letters and digits only, so compose and the shell read it correctly).
3. Add `DB_ROOT_PASSWORD=<value>` to `.env` (keep the file at mode 600).
4. Change both root accounts in MySQL:
   ```
   docker exec -it waha-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
   ALTER USER 'root'@'%' IDENTIFIED BY '<value>';
   ALTER USER 'root'@'localhost' IDENTIFIED BY '<value>';
   ```
5. Recreate the containers right away so the backend uses the new value: `docker compose up -d` (about 1-2 minutes of downtime).
6. Check the health URL, sign in to the admin, and run one backup by hand. The backup and restore scripts use the container's own password, so they follow the change.

Rollback: an older release tag has the password written as `root` in its compose file. After the change, going back to such a tag needs the password changed back with `ALTER USER` first. Do this change on its own, after a release has been stable.

---
## Admin web (nginx) and HTTPS

The admin dashboard and the HTTPS entry are one nginx container (`waha_admin_web`) defined in `docker-compose.yml` under the `web` profile.

| Setting | Default | On the server |
|---|---|---|
| `COMPOSE_PROFILES` | empty (nginx not started) | `web`: `docker compose up -d`, `deploy.sh` and a reboot bring nginx up together with the backend, on the same network |
| `NGINX_CONF_DIR` | empty (`./nginx/conf.d`, the configuration in this repo) | Leave empty on the current server. A server built from scratch with another domain, or HTTP only, sets it to its own folder |

- Nginx looks the backend up by name on each request. It starts even if the backend is not reachable yet (answers 502 until it is) and keeps working when the backend container is recreated.
- A backend deploy does not touch the nginx container unless its configuration changed. A web deploy does not touch the backend.
- Certificates: the nightly renewal is the root cron job with `docker run ... certbot renew` followed by `docker exec waha_admin_web nginx -s reload`. There is no certbot service in compose.
- If you change `nginx/conf.d/duck.conf`, apply it with `docker exec waha_admin_web nginx -t && docker exec waha_admin_web nginx -s reload`.

### Deploying a new web build (independent of the backend)
In `/home/ubuntu/waha/admin_build`: upload the build, keep `web` (current) and `web_old` (previous), then restart the container, because the mount is re-attached when it starts:
```
cd /home/ubuntu/waha/admin_build
unzip web.zip -d web_new
rm -rf web_old && mv web web_old && mv web_new web
docker restart waha_admin_web
```
Rollback: `mv web web_bad && mv web_old web && docker restart waha_admin_web`.
Order when both change: backend first, then web. An older web works with a newer backend; a newer web may need the newer backend.

### New server (everything together)
1. Install Docker, clone the repo into `/home/ubuntu/waha`.
2. Create `.env` (mode 600) with the real values, including `COMPOSE_PROFILES=web`.
3. Put the dashboard build in `admin_build/web` and the certificates in `certbot/conf` (or issue new ones).
4. `docker compose up -d --build`. Database, backend and nginx start together.
5. Install the cron jobs (backup and certificate renewal).

---
## Next release: one-time server steps

This release moves the nginx container into `docker-compose.yml`. On the server, before or at the deploy:
1. Add `COMPOSE_PROFILES=web` to `/home/ubuntu/waha/.env`.
2. Remove the old containers: `docker rm -f waha_admin_web certbot`.
3. Run `deployment/deploy.sh`. It recreates `waha_admin_web` from the main compose file (about 10 seconds of HTTPS downtime).

Check: `docker ps` shows `waha_admin_web` up with ports 80 and 443, `https://waha1.duckdns.org/api/config` returns 200.
Afterwards (next release): delete `docker-compose.prod.yml`.

Files changed in this release for this: `docker-compose.yml`, `nginx/conf.d/duck.conf`, `docker-compose.prod.yml` (marked deprecated), `.env.example`, `docs/deployment.md`.

