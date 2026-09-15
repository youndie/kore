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
# `distroless/cc` and NOT `distroless/base`: Kotlin/Native's exception handling imports thirteen
# `_Unwind_*` symbols from **libgcc_s**, which `base` does not carry. "It needs glibc" is the usual
# shorthand and it is the wrong reason — `base` has glibc and still fails.
#
# ONE STAGE AND ONE `COPY`. This file used to carry a debian stage whose only job was to hand over
# `libcrypt.so.1`, which the binary asked the loader for and never called: `platform.posix`'s klib
# manifest puts `-lcrypt` on every Linux link. `--as-needed` in `samples/service/build.gradle.kts`
# drops it along with `libresolv` and `libutil` — ten NEEDED entries down to seven, read with
# `readelf -d` before and after — so nothing is dragged across between images any more.
#
# What that removed is worth more than the two lines: a system library copied out of a builder
# couples the two images by glibc, the builder's having to be no newer than the runtime's, and a
# mismatch is invisible at build time and exits at startup with `GLIBC_2.38 not found` before any of
# the application's logging has run. With no copy there is no pair to get wrong. Re-run `readelf -d`
# after any dependency change; a new entry is a decision about the base image.
FROM gcr.io/distroless/cc-debian13 AS native
COPY build/bin/linuxX64/releaseExecutable/service.kexe /app/service
EXPOSE 8080
ENTRYPOINT ["/app/service"]
