
FROM alpine:3 as base
RUN apk add  --no-cache openjdk@package.java-version@
RUN jlink \
--module-path /opt/java/jmods \
--add-modules java.se,java.base,java.compiler,java.desktop,java.instrument,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,jdk.jfr,jdk.management,jdk.unsupported,jdk.crypto.ec \
--no-header-files \
--no-man-pages \
--output /opt/jdk-mini

FROM alpine:3
COPY --from=base /opt/jdk-mini /opt/jdk-mini
RUN apk add --no-cache coreutils
ENV JAVA_HOME=/opt/jdk-mini
ENV PATH="$PATH:$JAVA_HOME/bin"
COPY etc/ /etc/@package.name@/
ADD resources/* /data/
RUN chmod +x /data/entrypoint.sh
ENTRYPOINT /data/entrypoint.sh
