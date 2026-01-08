FROM ghcr.io/graalvm/jdk-community:21 as base
ADD resources/* /data/
RUN java -Djarmode=tools -jar /data/@package.name@.jar extract
RUN jdeps --ignore-missing-deps -q  \
--recursive \
--multi-release 21 \
--print-module-deps \
--class-path '/app/@package.name@/lib/*' \
/app/@package.name@/@package.name@.jar > deps.info
RUN jlink \
--module-path $JAVA_HOME/jmods \
--add-modules java.se,java.base,java.compiler,java.desktop,java.instrument,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,jdk.jfr,jdk.management,jdk.unsupported,org.graalvm.collections,org.graalvm.nativeimage,jdk.crypto.ec \
--no-header-files \
--no-man-pages \
--output /opt/jdk-mini

FROM debian:12-slim
COPY --from=base /opt/jdk-mini /opt/jdk-mini
COPY --from=base /app/@package.name@/ /@package.name@/
RUN apt-get update && apt-get install coreutils -y
ENV JAVA_HOME=/opt/jdk-mini
ENV PATH="$PATH:$JAVA_HOME/bin"
COPY etc/ /etc/@package.name@/

RUN chmod +x /etc/@package.name@/entrypoint.sh
# Starting non-fat jar
ENTRYPOINT ["/etc/@package.name@/entrypoint.sh"]