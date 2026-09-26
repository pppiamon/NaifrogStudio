FROM node:22-bookworm-slim
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY src ./src
ENV PORT=8787 HOST=0.0.0.0 DATA_DIR=/data CODEX_ENABLED=true
EXPOSE 8787
VOLUME ["/data"]
CMD ["node", "src/server.mjs"]
