# CI image for gdx-vulkan: Ubuntu 24.04 (pinned by digest) with Mesa's software Vulkan (lavapipe), a virtual X server, the
# Khronos validation layer and glslc. The Mesa *version* is checked against ci/mesa-version.txt by ci/run-checks.sh,
# because apt cannot pin a version that the archive later drops. Goldens are lavapipe renders: when the pin changes,
# regenerate them (see ci/README.md).
FROM ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip git \
        openjdk-17-jdk-headless cmake build-essential ninja-build \
        glslc libvulkan1 mesa-vulkan-drivers vulkan-validationlayers vulkan-tools \
        xvfb xauth libx11-6 libx11-dev libxcursor1 libxrandr2 libxi6 libxxf86vm1 libxinerama1 libxext6 libxrender1 libgl1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work
