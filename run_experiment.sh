#!/usr/bin/env bash

# Usage: ./run_experiment.sh <experiment_definition.json> <number_of_instances>

CANVAS_STATE_SQL_PATH="./canvas-evaluation-scripts/sample_generated_data/odox-7c-mar-12-2026/odox-7c-2032-eval-fixed.sql"
CANVAS_STATE_SQL_NAME=$(basename $CANVAS_STATE_SQL_PATH)
CANVAS_PG_CONTAINER="canvas-lms-postgres-1"

ODOBOT_CONFIG_PATH="./config/"
ODOBOT_LIBS_PATH="./libs/"
ODOBOT_DB_PATH="./db/"
ODOBOT_OUTPUT_PATH="./odobot_output/"



for i in {1..$2}; do
  run_experiment $1 "$i"
done

run_experiment(){
  local experiment_definition=$1
  local instance=$2

  # Stop any existing odobot container
  docker stop odobot && docker rm odobot

  # Copy over the .sql file containing the desired state of Canvas at the start of the experiment
  docker cp $CANVAS_STATE_SQL $CANVAS_PG_CONTAINER:/usr/src

  # DROP the existing database tables to ensure a clean slate for the experiment.
  docker exec -it $CANVAS_PG_CONTAINER psql -U postgres -d canvas_development -c "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA IF NOT EXISTS public;"

  # Execute the .sql file to set the Canvas State
  docker exec -it $CANVAS_PG_CONTAINER psql -U postgres -d canvas_development -f /usr/src/$CANVAS_STATE_SQL_NAME

  # Pull and run OdoBot
  docker run -p 8076:8076 -p 7080:7080 -v $ODOBOT_CONFIG_PATH:/application/config -v $ODOBOT_OUTPUT_PATH:/application/execution_events -v $ODOBOT_LIBS_PATH:/application/libs -v $ODOBOT_DB_PATH:/application/db  --name odobot ca.ualberta/odobot

  # Wait for OdoBot to be ready
  until curl -s http://localhost:8076/api/health; do
    echo "Waiting for OdoBot to be ready..."
    sleep 5
  done

   jq '.experimentId += "-$instance"' "$experiment_definition" > "${experiment_definition}_${instance}.json";

  # Trigger the experiment
  curl -X POST http://localhost:8076/api/evaluate?agent=odoBotNL -H "Content-Type: application/json" -d @"$experiment_definition"
}

