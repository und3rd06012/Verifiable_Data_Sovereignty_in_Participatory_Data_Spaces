FROM node:22-alpine
WORKDIR /app
COPY package.json tsconfig.json ./
RUN npm install --ignore-scripts --no-audit --no-fund
COPY src ./src
RUN npm run build
EXPOSE 8080
CMD ["node", "dist/index.js"]
