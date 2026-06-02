FROM gradle:jdk26 AS builder
WORKDIR /project
COPY . /project/
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:26-noble
ENV APP_DIR=/application
ENV APP_FILE=odobot.jar
ENV MAIN_VERTICLE=ca.ualberta.odobot.MainVerticle
ENV LAUNCHER_CLASS=ca.ualberta.odobot.Launcher
# Prevent interactive prompts during installation
ENV DEBIAN_FRONTEND=noninteractive

# Setup python for evaluation scripts
RUN apt-get update && \
    apt-get install -y --no-install-recommends python3 python3-pip && \
    rm -rf /var/lib/apt/lists/*


# https://stackoverflow.com/questions/75608323/how-do-i-solve-error-externally-managed-environment-every-time-i-use-pip-3
RUN python3 -m pip config set global.break-system-packages true

WORKDIR $APP_DIR
COPY --from=builder /project/build/libs/*-all.jar $APP_DIR/$APP_FILE

# Move over
# Evaluation scripts
COPY canvas-evaluation-scripts $APP_DIR/canvas-evaluation-scripts
COPY neo4j-plugins $APP_DIR/neo4j-plugins
# NOTE: config files shouldn't be included here as they contain secret API keys and we don't want those bundled into the image.
# Copy over ssl files
COPY ./ssl $APP_DIR/ssl
# OdoX firefox extension
COPY ./odo-sight.xpi $APP_DIR
# Trajectory processing pipeline configuration
COPY ./odo-pipelines.json $APP_DIR



WORKDIR $APP_DIR/canvas-evaluation-scripts
RUN ls $APP_DIR
RUN pwd && ls $APP_DIR/canvas-evaluation-scripts
RUN pip install -r requirements.txt

WORKDIR $APP_DIR

ENTRYPOINT ["sh","-c"]
CMD ["exec java -jar $APP_FILE run $MAIN_VERTICLE --launcher-class=$LAUNCHER_CLASS"]
