# Two images, one file, one source tree. `--target jvm` and `--target native`.
#
# THE BINARIES ARE BUILT OUTSIDE DOCKER and copied in. Building Kotlin inside the image means a
# Gradle and a Kotlin/Native toolchain in the build context on every run; the portfolio settled that
# question already and this follows it.
#
# ENTRYPOINT IS EXEC FORM in both stages, and that is the load-bearing line rather than a style
# choice. A shell-form entrypoint runs `/bin/sh -c`, which becomes PID 1 and does not forward
# SIGTERM to its child — so the process this sample exists to signal never receives the signal, the
# container is killed after the grace period, and the run looks exactly like a process that shut
# down instantly. Assertion A4 of research-oracle §2.5 is what turns that into a failure rather than
# a silent pass.

# --- the JVM build -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS jvm
WORKDIR /app
COPY build/libs/service-all.jar /app/service.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/service.jar"]

# --- the Kotlin/Native build -------------------------------------------------------------------
#
# `distroless/cc` and NOT `distroless/base`: the binary is dynamically linked against libgcc_s,
# libm, libpthread and friends — read with `ldd`, not assumed.
#
# It also needs **libcrypt.so.1**, which `cc` does not carry. That is copied in explicitly rather
# than discovered as a container that will not start; the same gap has been paid for elsewhere in
# this portfolio. `ldd` on the binary is the way to check it after any dependency change.
FROM debian:trixie-slim AS native-libs
RUN apt-get update && apt-get install -y --no-install-recommends libcrypt1 && rm -rf /var/lib/apt/lists/*

FROM gcr.io/distroless/cc-debian13 AS native
COPY --from=native-libs /lib/x86_64-linux-gnu/libcrypt.so.1 /lib/x86_64-linux-gnu/libcrypt.so.1
COPY build/bin/linuxX64/releaseExecutable/service.kexe /app/service
EXPOSE 8080
ENTRYPOINT ["/app/service"]
