FROM alpine/java:@package.java-version@ as base
#RUN apk add  --no-cache openjdk-@package.java-version@-jdk
#RUN apk add  --no-cache openjdk@package.java-version@
RUN jlink \
--module-path /opt/java/jmods \
--compress=2 \
--add-modules java.se,jdk.unsupported,jdk.crypto.ec \
--no-header-files \
--no-man-pages \
--output /opt/jdk-mini
FROM alpine:3
COPY --from=base /opt/jdk-mini /opt/jdk-mini
RUN apk add  --no-cache coreutils
ENV JAVA_HOME=/opt/jdk-mini
ENV PATH="$PATH:$JAVA_HOME/bin"
COPY etc/ /etc/@package.directory@/
ADD resources/* /data/
ENTRYPOINT ["java", "-jar", "/data/@package.name@.jar"]