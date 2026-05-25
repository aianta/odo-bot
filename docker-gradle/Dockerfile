FROM gradle:jdk26 AS builder
WORKDIR /project
COPY . /project/
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:26-noble
ENV APP_DIR=/application
ENV APP_FILE=odobot.jar
ENV MAIN_VERTICLE=ca.ualberta.odobot.MainVerticle
ENV LAUNCHER_CLASS=ca.ualberta.odobot.Launcher

WORKDIR $APP_DIR
COPY --from=builder /project/build/libs/*-fat.jar $APP_DIR/$APP_FILE

ENTRYPOINT ["sh","-c"]
CMD ["exec java -jar $APP_FILE run $MAIN_VERTICLE --launcher-class=$LAUNCHER_CLASS"]
