FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    autoconf autoconf-archive automake bison build-essential ca-certificates cmake \
    curl flex git gperf libtool-bin nasm ninja-build openjdk-21-jdk-headless \
    patch pkg-config python3 swig unzip wget zip && \
    apt-get clean

ARG NDK_VERSION=r28c
ARG NDK_SHA1=a7b54a5de87fecd125a17d54f73c446199e72a64
RUN curl --fail --location --retry 3 "https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip" -o /tmp/ndk.zip && \
    printf '%s  /tmp/ndk.zip\n' "$NDK_SHA1" | sha1sum --check && \
    unzip -q /tmp/ndk.zip -d /opt && rm /tmp/ndk.zip

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    ANDROID_NDK_HOME=/opt/android-ndk-r28c \
    NDK_ROOT=/opt/android-ndk-r28c \
    VCPKG_ROOT=/work/vcpkg \
    VCPKG_MAX_CONCURRENCY=3

COPY mega-native-build.sh /usr/local/bin/mega-native-build
WORKDIR /work
ENTRYPOINT ["bash", "/usr/local/bin/mega-native-build"]
