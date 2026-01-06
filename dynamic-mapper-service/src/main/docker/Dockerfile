FROM ghcr.io/graalvm/jdk-community:21 as base
RUN jlink \
--module-path $JAVA_HOME/jmods \
--add-modules java.se,jdk.unsupported,jdk.crypto.ec \
--no-header-files \
--no-man-pages \
--output /opt/jdk-mini

FROM debian:12-slim
COPY --from=base /opt/jdk-mini /opt/jdk-mini
RUN apt-get update && apt-get install coreutils -y
ENV JAVA_HOME=/opt/jdk-mini
ENV PATH="$PATH:$JAVA_HOME/bin"
ADD resources/* /data/
#RUN $JAVA_HOME/bin/java --version
# Extracting Fat jar
RUN java -Djarmode=tools -jar /data/@package.name@.jar extract
RUN ls -al /
# Starting non-fat jar
ENTRYPOINT ["java", "-jar", "/@package.name@/@package.name@.jar"]