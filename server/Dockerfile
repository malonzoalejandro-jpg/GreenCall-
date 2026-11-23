FROM node:22-alpine
WORKDIR /app
COPY server/package*.json ./
RUN npm install --omit=dev
COPY server/schema.sql ./schema.sql
COPY server/src ./src
ENV NODE_ENV=production
EXPOSE 8080
CMD ["npm","start"]
