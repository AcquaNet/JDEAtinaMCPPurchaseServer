# Build: imagen oficial de Maven con Eclipse Temurin JDK 25 (pom.xml exige java.version=25)
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

# Cache de dependencias en su propia capa (solo se re-descarga si cambia el pom.xml)
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Runtime: solo JRE, sin toolchain de build
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

# curl solo para el HEALTHCHECK (no viene instalado en la imagen base)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system jdemcp && useradd --system --gid jdemcp --home /app --shell /usr/sbin/nologin jdemcp

COPY --from=build /app/target/*.jar /app/app.jar

# ./data es donde vive el H2 file DB (identity_mapping), ver spring.datasource.url
# por perfil -- se monta como volumen en docker-compose para persistir entre reinicios.
RUN mkdir -p /app/data && chown -R jdemcp:jdemcp /app

USER jdemcp
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -sf http://127.0.0.1:8080/.well-known/oauth-protected-resource || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
