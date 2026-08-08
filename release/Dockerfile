FROM eclipse-temurin:21-jre-ubi9-minimal@sha256:3c624d2b86ddcf884f2ea01355da5c0eefaeb93904c61bc0d3a775b176f04947

WORKDIR /opt/graphcrud
COPY --chown=65532:65532 launcher/build/install/launcher/ ./
USER 65532:65532
ENTRYPOINT ["/opt/graphcrud/bin/launcher"]
