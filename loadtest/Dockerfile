# hey (https://github.com/rakyll/hey) built for the cluster's own CPU architecture,
# so the load test runs inside kind instead of through kubectl port-forward.
FROM golang:1.23-alpine AS build
RUN go install github.com/rakyll/hey@v0.1.4

FROM alpine:3.20
COPY --from=build /go/bin/hey /usr/local/bin/hey
ENTRYPOINT ["hey"]
