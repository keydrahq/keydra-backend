# The API alone, for a deployment that serves the interface separately.
#
#   podman build --ulimit nofile=16384:16384 -t keydra-backend:dev -f Containerfile .
#
# The ordinary deployment is the single image built in keydrahq/keydra, which carries the
# interface inside it so one container answers both and nothing has to be told where the API
# lives. This one exists for the deployments where that is the wrong shape: an interface on a
# CDN, several API replicas behind one set of static files, or an estate that already has
# somewhere to put a single-page application.
#
# Pair it with quay.io/keydrahq/keydra-ui, which serves the interface and routes /api and
# /graphql here. They have to be one origin to the browser — the session is a cookie — which
# is why that image proxies rather than the interface calling across origins.
#
# The ulimit is not decoration. Rootless Podman passes the shell's own file-descriptor limit
# into the build, and javac compiling this many sources against this many jars runs out of
# descriptors long before it runs out of anything else — the error it gives is "Too many open
# files" against a random jar, which reads like a corrupt dependency and is not one.

# --- Stage 1: build -----------------------------------------------------------
# UBI 10's OpenJDK 21 image, which carries Maven 3.9 already — so this is a Maven image and
# a JDK image at once, and the version is pinned by the tag. That pin is the same guarantee the
# project's wrapper gives a developer, without a download at build time; and the download is
# what fails here, since this base image carries neither curl nor wget for the wrapper to use.
#
# root only because the image runs as uid 185 by default and /build would not be writable.
# Nothing survives this stage but target/, so the builder's user is not a security property.
FROM registry.access.redhat.com/ubi10/openjdk-21:1.24 AS build

USER root
WORKDIR /build

# The descriptor first, then dependencies, then source: dependencies change far less often
# than code, so that layer survives almost every rebuild.
COPY .mvn/ .mvn/
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src/ src/
# Tests need containers, which a build container does not have; they run in CI.
RUN mvn -B -ntp package -DskipTests

# --- Stage 2: what actually ships ---------------------------------------------
# The runtime variant: a JRE without the compiler, which is a few hundred megabytes a running
# server has no use for and one more tool an attacker would find waiting.
#
# This is the stage where a Red Hat base earns its keep rather than merely matching a policy —
# it is the only image still running a month from now, so whose errata feed and patch cadence
# it is on is a real answer to a real question. The builder above is thrown away.
#
# UBI 10 rather than 9, and the difference is measurable rather than a preference for the
# newer number: 332 MB against 396, and neither python3 nor expat is installed — which
# between them carried sixty-one of the two hundred and seventy package vulnerabilities the
# registry's scanner found in the UBI 9 build. Everything this file depends on is the same:
# uid 185 in group 0, Java 21.0.12.1, curl and no wget.
FROM registry.access.redhat.com/ubi10/openjdk-21-runtime:1.24

# No user is created here: the image already runs as uid 185, and files are given to group 0
# so the container still works when a platform assigns it some arbitrary uid instead — which
# is what OpenShift does, and the reason group 0 rather than 185:185.
USER root
WORKDIR /app

COPY --from=build --chown=185:0 /build/target/quarkus-app/lib/ lib/
COPY --from=build --chown=185:0 /build/target/quarkus-app/*.jar ./
COPY --from=build --chown=185:0 /build/target/quarkus-app/app/ app/
COPY --from=build --chown=185:0 /build/target/quarkus-app/quarkus/ quarkus/

USER 185
# 8181 is the API. 9001 is health, readiness and metrics, and it is a separate port because
# what they answer describes the installation to anybody who can reach it — so whatever
# publishes the first must not publish the second.
EXPOSE 8181 9001

# Reads the container's own memory limit rather than the host's, so a limited container does
# not size its heap for a machine it cannot use.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# curl rather than wget: this base is built on ubi9-minimal, which carries the first and not
# the second. Kept although Podman drops it — an image built in the OCI format has nowhere to
# put a healthcheck and says so — because `--format docker` keeps it, and an image somebody
# runs by hand then behaves the way the manifests make it behave.
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s \
    CMD ["sh", "-c", "curl -sf http://localhost:9001/q/health/ready || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar quarkus-run.jar"]
