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
