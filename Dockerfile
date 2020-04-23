FROM oracle/graalvm-ce:20.0.0-java11 as builder

WORKDIR /app
COPY . /app

RUN gu install native-image

RUN ./sbt graalvm-native-image:packageBin

FROM frolvlad/alpine-glibc
#FROM gcr.io/distroless/static
#FROM gcr.io/distroless/base
#FROM scratch

WORKDIR /var/tmp

#COPY --from=builder /etc/hosts /etc/hosts

#COPY --from=builder /opt/graalvm-ce-java11-20.0.0/lib/security/cacerts /cacerts

#COPY --from=builder /opt/graalvm-ce-java11-20.0.0/lib/libsunec.so /libsunec.so

COPY --from=builder /app/target/graalvm-native-image/javadoccentral /javadoccentral

ENTRYPOINT ["/javadoccentral"]
#, "-Djavax.net.ssl.trustStore=/cacerts", "-Djavax.net.ssl.trustAnchors=/cacerts"]
