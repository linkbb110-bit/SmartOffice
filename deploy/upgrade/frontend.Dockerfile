FROM node:22-alpine AS build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/tsconfig*.json frontend/env.d.ts frontend/index.html frontend/vite.config.ts ./
COPY frontend/src ./src
COPY frontend/public ./public
ENV VITE_API_BASE_URL=/api
RUN npm run build

FROM nginx:1.28-alpine
COPY deploy/upgrade/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html
EXPOSE 80
