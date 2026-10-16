FROM node:22-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig*.json env.d.ts index.html vite.config.mjs ./
COPY src ./src
ENV VITE_API_BASE_URL=/api
RUN npm run build

FROM nginx:1.28-alpine
COPY deploy/upgrade/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 80
