FROM golang:1.22-alpine AS build

WORKDIR /src

ENV GOWORK=off

COPY libs ./libs
COPY services/ussd-service ./services/ussd-service

WORKDIR /src/services/ussd-service

RUN go mod download
RUN CGO_ENABLED=0 GOOS=linux go build -o /out/app-release

FROM alpine:latest

RUN mkdir /app

COPY --from=build /out/app-release /app/app-release
COPY services/ussd-service/templates /app/templates
COPY services/ussd-service/locales /app/locales
COPY services/ussd-service/config.yml /app/config.yml
COPY services/ussd-service/ussd_config.json /app/ussd_config.json

CMD [ "/app/app-release"]