FROM node:22-bookworm-slim AS builder

WORKDIR /app

ARG NEXT_PUBLIC_API_HOST=http://localhost:3001
ENV NEXT_PUBLIC_API_HOST=${NEXT_PUBLIC_API_HOST}

COPY chivas-rumberas-frontend/package*.json ./
RUN npm ci

COPY chivas-rumberas-frontend/ ./
RUN rm -f .env.local && npm run build

FROM node:22-bookworm-slim AS runner

WORKDIR /app

ENV NODE_ENV=production
ENV PORT=3000

COPY --from=builder /app/package*.json ./
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/.next ./.next
COPY --from=builder /app/public ./public

EXPOSE 3000

CMD ["npm", "run", "start"]
