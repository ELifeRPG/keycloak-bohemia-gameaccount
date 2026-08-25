FROM mcr.microsoft.com/devcontainers/java:21

ENV DEBIAN_FRONTEND=noninteractive

# Maven isn't preinstalled in the base image (Java only). This repo's own
# build/test workflow otherwise runs Maven via a pinned
# `maven:3.9-eclipse-temurin-21` container per-command (see README.md), to
# keep the exact build environment reproducible outside this devcontainer
# too — but for interactive day-to-day use inside the devcontainer it's
# nicer to have `mvn` directly on PATH. Debian trixie's apt-provided 3.9.9
# matches that pinned image's major.minor.
RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*
