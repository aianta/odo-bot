#!/usr/bin/env bash

# Usage: ./run_experiment_webvoyager.sh <run.sh script path> <number_of_instances>

WEBVOYAGER_SCRIPT=$1
CANVAS_PG_CONTAINER="canvas-lms-postgres-1"
CANVAS_STATE_SQL_PATH="/home/ssrg/aianta/cascon-2026.sql"
CANVAS_STATE_SQL_NAME=$(basename $CANVAS_STATE_SQL_PATH)
CANVAS_REDIS_CONTAINER="canvas-lms-redis-1"
CANVAS_PATH="/home/ssrg/aianta/canvas-lms"
WEBVOYAGER_PATH=$(dirname $WEBVOYAGER_SCRIPT)

run_experiment(){
  # Go to canvas working dir
  cd "$CANVAS_PATH"

  echo "Shutting down canvas environment"
  # Shutdown Canvas Environment
  docker compose down

  echo "Starting up postgres & redis to reset environment"
  # Start only postgres & redis
  docker compose up -d postgres

  docker compose up -d redis

  echo "Copying over $CANVAS_STATE_SQL_NAME..."
  # Copy over the .sql file containing the desired state of Canvas at the start of the experiment
  docker cp "${CANVAS_STATE_SQL_PATH}" "${CANVAS_PG_CONTAINER}:/usr/src"

  echo "Dropping existing schema"
  # DROP the existing database tables to ensure a clean slate for the experiment.
  docker exec -it $CANVAS_PG_CONTAINER psql -U postgres -d canvas_development -c "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA IF NOT EXISTS public;"

  echo "Loading database snapshot"
  # Execute the .sql file to set the Canvas State
  docker exec -it $CANVAS_PG_CONTAINER psql -U postgres -d canvas_development -f /usr/src/$CANVAS_STATE_SQL_NAME

  echo "Flushing redis"
  docker exec -it $CANVAS_REDIS_CONTAINER redis-cli flushdb

  docker compose up -d

  sleep 60

  # Go to WebVoyager working dir
  cd "$WEBVOYAGER_PATH"


  "$WEBVOYAGER_SCRIPT"
}

source /home/ssrg/miniconda3/etc/profile.d/conda.sh

conda activate webvoyager

for i in $(seq 1 $2); do
  run_experiment
done
