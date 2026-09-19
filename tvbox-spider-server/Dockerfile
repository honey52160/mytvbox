# tvbox-spider-server 运行镜像
# 依赖本机已编译产物（build/classes、libs、config、plugins）。
# 若改动 Java 源码，请先在本机执行 ./build.sh 重新编译，再重建本镜像。
FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY build/classes /app/build/classes
COPY libs /app/libs
COPY config /app/config
COPY plugins /app/plugins

RUN mkdir -p /app/run /app/data \
 # 健康检查用 curl（JDK 镜像默认无 curl）
 && apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

EXPOSE 9978

# 进程存活 + 接口可用健康检查：/health 由 HttpRouter 提供
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:9978/health || exit 1

# 与 start.sh 等价：JDK 内置 HTTP 服务 + 指定 tvbox.home
CMD ["java", "--add-modules", "jdk.httpserver", "-Dfile.encoding=UTF-8", \
     "-Dtvbox.home=/app", "-cp", "/app/build/classes:/app/libs/*", \
     "com.github.catvod.host.HostMain"]
