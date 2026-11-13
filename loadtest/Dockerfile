# hey (https://github.com/rakyll/hey), cross-compiled for the cluster's CPU architecture
# (arm64 for kind on Apple silicon, amd64 for GKE), so the load test runs inside the cluster.
FROM --platform=$BUILDPLATFORM golang:1.23-alpine AS build
ARG TARGETOS TARGETARCH
# go install puts a cross-compiled binary in /go/bin/<os>_<arch>/, a native one in /go/bin/
RUN GOOS=$TARGETOS GOARCH=$TARGETARCH go install github.com/rakyll/hey@v0.1.4 \
 && mkdir /out && find /go/bin -name hey -type f -exec cp {} /out/ \;

FROM alpine:3.20
COPY --from=build /out/hey /usr/local/bin/hey
ENTRYPOINT ["hey"]
