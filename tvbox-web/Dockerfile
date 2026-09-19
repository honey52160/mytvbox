# tvbox-web 运行镜像（零第三方依赖，仅需 Node >= 18 + ffmpeg）
FROM node:18-slim

WORKDIR /app

# /live 的 RTMP/RTSP 转封装依赖 ffmpeg
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

COPY package.json ./
COPY server.js ./
COPY web ./web

ENV PORT=8888
ENV HOST=0.0.0.0
# 合并部署时指向同 compose 网络内的 spider 服务；独立运行可改 127.0.0.1:9978
ENV SPIDER_UPSTREAM=http://spider:9978

EXPOSE 8888

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:8888/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server.js"]
