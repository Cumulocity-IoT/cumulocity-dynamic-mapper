FROM ghcr.io/graalvm/jdk-community:21 as base
RUN jlink \
--module-path $JAVA_HOME/jmods \
--add-modules java.se,jdk.unsupported,jdk.crypto.ec \
--no-header-files \
--no-man-pages \
--output /opt/jdk-mini
ADD resources/* /data/
RUN java -Djarmode=tools -jar /data/@package.name@.jar extract
RUN ls -al /app/@package.name@/

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