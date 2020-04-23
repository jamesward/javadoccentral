FROM oracle/graalvm-ce:20.0.0-java11 as builder

WORKDIR /app
COPY . /app

RUN gu install native-image

RUN curl -L -o musl.tar.gz https://github.com/gradinac/musl-bundle-example/releases/download/v1.0/musl.tar.gz && \
    tar -xvzf musl.tar.gz

RUN ./sbt graalvm-native-image:packageBin

FROM scratch

WORKDIR /var/tmp

COPY --from=builder /app/target/graalvm-native-image/javadoccentral /javadoccentral

ENTRYPOINT ["/javadoccentral"]
