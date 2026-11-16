FROM node:22-bookworm-slim AS builder

RUN apt-get update -y && apt-get install -y --no-install-recommends openssl ca-certificates python3 make g++ \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY backend-express/package*.json ./
RUN npm ci

COPY backend-express/prisma ./prisma
RUN npx prisma generate --schema=prisma/schema.prisma

COPY backend-express/ ./
RUN npm run build

FROM node:22-bookworm-slim AS runner

RUN apt-get update -y && apt-get install -y --no-install-recommends openssl ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/package*.json ./
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/prisma ./prisma
COPY --from=builder /app/dist ./dist
COPY infra/docker/express/mock-integrations.cjs /opt/mock-integrations.cjs

ENV NODE_ENV=production
ENV PORT=3000
ENV NODE_OPTIONS="--require /opt/mock-integrations.cjs"

EXPOSE 3000

CMD ["node", "dist/interface/server.js"]
