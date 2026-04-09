# Wolfbook Production Deployment

## Target
- Domain: `https://wolfbook.godlei8.top`
- External entry: `443` via Nginx
- Internal backend port: `8110`
- Internal admin static port: `6110`

## Required files
1. Copy `deploy/.env.prod.example` to `deploy/.env.prod`.
2. Fill every secret in `deploy/.env.prod`.
3. Put TLS files into `deploy/certs/`:
   - `deploy/certs/fullchain.pem`
   - `deploy/certs/privkey.pem`

## Pre-deploy
1. Confirm `wolfbook.godlei8.top` resolves to the production server.
2. Confirm HTTPS certificate is valid.
3. Update `miniprogram-vue/src/manifest.json` with the real `mp-weixin.appid`.
4. Configure WeChat legal domains:
   - `request`
   - `uploadFile`
   - `downloadFile`
5. Prepare MySQL schema by executing `backend/src/main/resources/schema.sql` once against the production MySQL database before first boot.

## Start services
```bash
docker compose --env-file deploy/.env.prod -f docker-compose.prod.yml build
docker compose --env-file deploy/.env.prod -f docker-compose.prod.yml up -d
```

## Smoke checks
1. Open `https://wolfbook.godlei8.top/console/`.
2. Open `https://wolfbook.godlei8.top/api/boards`.
3. Log in to the admin console with `admin` and the password from `ADMIN_INIT_PASSWORD`.
4. Change the admin password immediately after first login.
5. Upload a board cover, role portrait, and AI knowledge document to confirm COS returns HTTPS URLs.
6. Publish at least one AI knowledge version before testing the assistant.

## Notes
- Databases are intentionally not exposed to the public internet.
- Backend Swagger is disabled in the `prod` profile.
- The backend refuses to bootstrap a production admin user unless `ADMIN_INIT_PASSWORD` is provided.
- Legacy `/uploads/*` requests are still proxied to the backend for backward compatibility.
