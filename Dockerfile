FROM openjdk:8-jdk-alpine as builder
# ----
# Install Maven
RUN apk add --no-cache curl tar bash
ARG MAVEN_VERSION=3.6.3
ARG USER_HOME_DIR="/root"
RUN mkdir -p /usr/share/maven && \
curl -fsSL http://apache.osuosl.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz | tar -xzC /usr/share/maven --strip-components=1 && \
ln -s /usr/share/maven/bin/mvn /usr/bin/mvn
ENV MAVEN_HOME /usr/share/maven
ENV MAVEN_CONFIG "$USER_HOME_DIR/.m2"
# speed up Maven JVM a bit
ENV MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
ENTRYPOINT ["/usr/bin/mvn"]
# ----
# Install project dependencies and keep sources
# make source folder
RUN mkdir -p /usr/src/app
WORKDIR /usr/src/app
# install maven dependency packages (keep in image)
COPY pom.xml /usr/src/app
COPY src /usr/src/app/src
RUN mvn -T 1C install && rm -rf target
# copy other source files (keep in image)
RUN mvn -U package
FROM openjdk:8-jdk-alpine

WORKDIR /root/
RUN apk add --no-cache bash
COPY --from=builder /usr/src/app/target/kurento-player-6.15.0-exec.jar .
ADD https://raw.githubusercontent.com/vishnubob/wait-for-it/master/wait-for-it.sh /bin/wait-for-it.sh
RUN chmod +x /bin/wait-for-it.sh

ENV KMS_ADDR $KMS_ADDR
ENV KMS_PORT $KMS_PORT

CMD ["/bin/sh", "-c", "/bin/wait-for-it.sh ${KMS_ADDR}:${KMS_PORT} -s -t 30 -- java -jar -Dkms.url=ws://${KMS_ADDR}:${KMS_PORT}/kurento /root/kurento-player-6.15.0-exec.jar"]

