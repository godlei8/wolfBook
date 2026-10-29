# 只装运行时：app.jar 在本地 `./mvnw -DskipTests package` 后从 backend/target 拷进本目录（已 gitignore）
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY app.jar /app/app.jar

EXPOSE 8110

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} -jar /app/app.jar"]
