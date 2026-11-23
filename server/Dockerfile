FROM node:22-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev
COPY schema.sql ./schema.sql
COPY src ./src
ENV NODE_ENV=production
EXPOSE 8080
CMD ["npm","start"]
