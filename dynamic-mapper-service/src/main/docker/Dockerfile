FROM alpine:3 as base
RUN apk --no-cache add curl
RUN curl --output graalvm-jdk-21_linux-x64_bin.tar.gz https://download.oracle.com/graalvm/21/latest/graalvm-jdk-21_linux-x64_bin.tar.gz
RUN mkdir -p /opt/graalvm
RUN tar -xzf graalvm-jdk-21_linux-x64_bin.tar.gz --directory /opt/graalvm --strip-components=1

FROM alpine:3
COPY --from=base /opt/graalvm /opt/graalvm
RUN apk add  --no-cache coreutils
ENV JAVA_HOME=/opt/graalvm
ENV PATH="$PATH:$JAVA_HOME/bin"
ADD resources/* /data/
RUN $JAVA_HOME/bin/java --version
# Extracting Fat jar
RUN java -Djarmode=tools -jar /data/@package.name@.jar extract
# Starting non-fat jar
ENTRYPOINT ["java", "-jar", "/data/@package.name@/@package.name@.jar"]