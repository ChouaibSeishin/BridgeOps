FROM node@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f AS build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM nginx@sha256:ed84151ed2d47ef93c0939eb9b9754c6990db6092cd2081e11988a47a83d47ea
COPY infra/docker/nginx.conf /etc/nginx/nginx.conf
COPY --from=build /workspace/dist/ /usr/share/nginx/html/
USER nginx
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
