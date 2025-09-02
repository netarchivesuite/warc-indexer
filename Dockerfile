FROM openjdk:21-jdk-slim AS builder

RUN apt-get -y update; apt-get -y install curl jq git

ARG MAVEN_VERSION=3.9.11
ARG USER_HOME_DIR="/home/maven"

RUN adduser maven --disabled-password --gecos ""

RUN mkdir -p /usr/share/maven /usr/share/maven/ref \
  && curl -fsSL https://dlcdn.apache.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz \
  | tar -xzC /usr/share/maven --strip-components=1 \
  && ln -s /usr/share/maven/bin/mvn /usr/bin/mvn

ENV http_proxy=158.39.103.138:3128
ENV https_proxy=158.39.103.138:3128

ENV MAVEN_HOME=/usr/share/maven
ENV MAVEN_CONFIG="$USER_HOME_DIR/.m2"
ENV JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=158.39.103.138 -Dhttps.proxyPort=3128 -Dhttp.nonProxyHosts=localhost|127.0.0.1|172.*|docker|*.nb.no"

RUN mkdir $MAVEN_CONFIG
# COPY settings.xml $MAVEN_CONFIG/settings.xml
RUN mkdir $MAVEN_CONFIG/repository
RUN chown maven:maven $USER_HOME_DIR -R

USER maven
CMD ["mvn"]

FROM builder AS runner

WORKDIR /project

COPY . .

RUN mvn clean package -D skipTests

CMD ["java", "-jar", "target/warc-indexer-3.4.0-SNAPSHOT-jar-with-dependencies.jar"]
