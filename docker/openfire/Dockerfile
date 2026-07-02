# syntax=docker/dockerfile:1

# Build from the repository root, not this directory:
#   docker compose build openfire
#   ./docker/openfire/build.sh
#   docker build -f docker/openfire/Dockerfile -t openfire-monitoring-plugin-openfire .

ARG OPENFIRE_VERSION=5.1.0

# ---------------------------------------------------------------------------
# Monitoring plugin
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS plugin-build

WORKDIR /build
COPY pom.xml plugin.xml changelog.html readme.html ./
COPY src ./src
COPY docker/maven-settings-for-openfire-plugins.xml /root/.m2/settings.xml

RUN --mount=type=cache,target=/root/.m2,id=monitoring-plugin \
    mvn -B package -DskipTests

# ---------------------------------------------------------------------------
# OpenFire: extract pom.xml files for a stable dependency cache layer
# (mirrors upstream "poms" stage)
# ---------------------------------------------------------------------------
FROM alpine:3.21 AS openfire-poms

ARG OPENFIRE_VERSION
RUN apk add --no-cache git

WORKDIR /usr/src
RUN git clone --depth 1 --branch "v${OPENFIRE_VERSION}" https://github.com/igniterealtime/Openfire.git . \
    && find . -type f ! -name pom.xml ! -name '*.jar' -delete \
    && find . -type d -empty -delete

# ---------------------------------------------------------------------------
# OpenFire: compile from source (based on upstream Dockerfile)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25 AS openfire-build

ARG OPENFIRE_VERSION
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /usr/src
RUN git clone --depth 1 --branch "v${OPENFIRE_VERSION}" https://github.com/igniterealtime/Openfire.git . \
    && chmod +x mvnw

# Prefetch dependencies using pom-only layer for better cache hits on plugin-only changes.
COPY --from=openfire-poms /usr/src/ .
RUN --mount=type=cache,target=/tmp/m2_repo,id=openfire_m2_${OPENFIRE_VERSION} \
    ./mvnw -B dependency:resolve-plugins -Dmaven.test.skip=true -Dmaven.repo.local=/tmp/m2_repo \
    && ./mvnw -B de.qaware.maven:go-offline-maven-plugin:resolve-dependencies \
        -Dmaven.test.skip=true -Dmaven.repo.local=/tmp/m2_repo

# Restore full source tree and compile OpenFire.
RUN git checkout -- . && git clean -fd \
    && chmod +x mvnw
RUN --mount=type=cache,target=/tmp/m2_repo,id=openfire_m2_${OPENFIRE_VERSION} \
    ./mvnw -B install -Dmaven.compiler.release=25 -Dmaven.test.skip=true -Dmaven.repo.local=/tmp/m2_repo \
    && sed -i 's/\r$//' distribution/target/distribution-base/bin/openfire.sh

# ---------------------------------------------------------------------------
# Runtime user (mirrors upstream "skeleton-runtime" stage)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS skeleton-runtime

ENV OPENFIRE_USER=openfire \
    OPENFIRE_DIR=/usr/local/openfire \
    OPENFIRE_DATA_DIR=/var/lib/openfire \
    OPENFIRE_LOG_DIR=/var/log/openfire

RUN apt-get update \
    && apt-get install -y --no-install-recommends adduser \
    && adduser --disabled-password --quiet --system \
        --home "${OPENFIRE_DATA_DIR}" \
        --gecos "Openfire XMPP server" \
        --group "${OPENFIRE_USER}" \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------------------
# Final runtime image
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

ARG OPENFIRE_VERSION
ENV OPENFIRE_USER=openfire \
    OPENFIRE_DIR=/usr/local/openfire \
    OPENFIRE_DATA_DIR=/var/lib/openfire \
    OPENFIRE_LOG_DIR=/var/log/openfire \
    OPENFIRE_VERSION="${OPENFIRE_VERSION}"

COPY --from=skeleton-runtime /etc/passwd /etc/shadow /etc/group /etc/
COPY --chown=${OPENFIRE_USER}:${OPENFIRE_USER} --from=skeleton-runtime ${OPENFIRE_DATA_DIR} ${OPENFIRE_DATA_DIR}

COPY --chmod=0755 docker/openfire/entrypoint.sh /sbin/entrypoint.sh
COPY --chown=${OPENFIRE_USER}:${OPENFIRE_USER} \
    --from=openfire-build /usr/src/distribution/target/distribution-base ${OPENFIRE_DIR}

RUN mv ${OPENFIRE_DIR}/conf ${OPENFIRE_DIR}/conf_org \
    && mv ${OPENFIRE_DIR}/plugins ${OPENFIRE_DIR}/plugins_org \
    && mv ${OPENFIRE_DIR}/resources/security ${OPENFIRE_DIR}/resources/security_org

COPY --from=plugin-build /build/target/monitoring-openfire-plugin-assembly.jar /opt/plugins/monitoring.jar
COPY docker/openfire/conf/openfire.xml /opt/openfire-config/openfire.xml

LABEL org.opencontainers.image.title="Openfire with Monitoring Plugin" \
      org.opencontainers.image.version="${OPENFIRE_VERSION}"

WORKDIR ${OPENFIRE_DIR}

HEALTHCHECK --interval=30s --timeout=10s --start-period=3m --retries=5 \
    CMD bash -c "(echo > /dev/tcp/127.0.0.1/5222) 2>/dev/null || exit 1"

EXPOSE 5222 5269 7070 7443 9090 9091
VOLUME ["${OPENFIRE_DATA_DIR}", "${OPENFIRE_LOG_DIR}"]

ENTRYPOINT ["/sbin/entrypoint.sh"]
