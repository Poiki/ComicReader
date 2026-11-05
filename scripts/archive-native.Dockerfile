FROM comicreader-mega-toolchain:ndk28c

RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc-mingw-w64-x86-64 libz-mingw-w64-dev zlib1g-dev && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

ENTRYPOINT ["bash", "/repo/scripts/archive-native-build.sh"]
