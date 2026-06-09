# OdoBot 

# Building Docker Image 

```
docker build --progress=plain -t aianta/odobot -f docker-gradle/Dockerfile .    
```

# Running OdoBot via Docker


## Volume Mounts
* `/config`: contains configuration YAML files for OdoBot services
* `/execution_events`: When OdoBot executes tasks on a web application, this folder is populated with logs, telemetry and experiment results.
* `/libs`: External libraries and tools required for OdoBot to function. 
* `/db`: The folder containing SQLite (`odobot.db`) and Embedded Neo4J data. These store the application model OdoBot will use during task execution. 

## Port Bindings
* `8076`: This is the port on which OdoBot exposes its `/api/evaluate` endpoint where tasks can be sent for evaluation.
* `7080`: This is the port on which OdoBot exposes its guidance service

```
docker run -p 8076:8076 -p 7080:7080 -v ./config:/application/config -v ./execution_events:/application/execution_events -v ./libs:/application/libs -v ./db:/application/db  --name odobot aianta/odobot
```

# Running Experiments

`git clone --recurse-submodules git@github.com:aianta/odo-bot.git`