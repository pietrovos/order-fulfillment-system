# ---- build ---------------------------------------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# ---- serve ---------------------------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist/frontend/browser /usr/share/nginx/html
ENV BACKEND_URL=http://backend:8080
# Only substitute our variable; nginx's own $vars must survive envsubst.
ENV NGINX_ENVSUBST_FILTER=BACKEND_URL
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
