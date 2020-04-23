FROM oracle/graalvm-ce:20.0.0-java11 as builder

WORKDIR /app
COPY . /app

RUN gu install native-image

RUN ./sbt graalvm-native-image:packageBin

FROM scratch

WORKDIR /var/tmp

WORKDIR /lib
COPY --from=builder /usr/lib64/ld-linux-x86-64.so.2 /lib
COPY --from=builder /usr/lib64/libc.so.6 /lib
COPY --from=builder /usr/lib64/libnss_dns.so.2 /lib
COPY --from=builder /usr/lib64/libnss_files.so.2 /lib
COPY --from=builder /usr/lib64/libresolv.so.2 /lib

ENV LD_LIBRARY_PATH="/lib"

COPY --from=builder /app/target/graalvm-native-image/javadoccentral /javadoccentral

ENTRYPOINT ["/javadoccentral"]
