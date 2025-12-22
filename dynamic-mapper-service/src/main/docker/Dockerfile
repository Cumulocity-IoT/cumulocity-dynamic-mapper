FROM ghcr.io/graalvm/jdk-community:21 as base
ADD resources/* /data/
# Extracting Fat jar
RUN java -Djarmode=tools -jar /data/@package.name@.jar extract
# Starting non-fat jar
ENTRYPOINT ["java", "-jar", "/data/@package.name@/@package.name@.jar"]