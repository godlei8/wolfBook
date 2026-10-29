# 只装运行时：dist 在本地 admin 下 `npm run build` 后拷进本目录，nginx.prod.conf 拷自 admin/（两者都已 gitignore）
FROM nginx:1.27-alpine

COPY nginx.prod.conf /etc/nginx/conf.d/default.conf
COPY dist /usr/share/nginx/html/console

EXPOSE 6110
