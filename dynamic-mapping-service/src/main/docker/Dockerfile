# FROM alpine:3
FROM eclipse-temurin:24-jdk-alpine
# Install coreutils if not present. Command exit 0 is required if coreutils are presend int the image.
# If the coreutils are present ! command -v coreutils >/dev/null 2>&1 will set the exit code of the command to 1 failing the docker build 
RUN command -v apk >/dev/null 2>&1 && ! command -v coreutils >/dev/null 2>&1 && apk add --no-cache coreutils; exit 0
# Install java if not present.
# RUN command -v apk >/dev/null 2>&1 && ! command -v java >/dev/null 2>&1 && apk add --no-cache openjdk24; exit 0
# Validate that required dependencies are installed
RUN command -v coreutils >/dev/null 2>&1
RUN command -v java >/dev/null 2>&1
COPY etc/ /etc/dynamic-mapping-service/
ADD resources/* /data/
RUN chmod +x /data/entrypoint.sh
ENTRYPOINT /data/entrypoint.sh
