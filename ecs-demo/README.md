# KEDA Demo Setup

![scaler gfx](https://github.com/SolaceLabs/pq-demo/blob/main/readme/scaler.png)

KEDA (Kubernetes Event-Driven Autoscaler) is a Kubernetes Resource that allows you to scale pods/containers/workloads based on some metric(s).  One of the scalers included is for Solace PubSub+, specifically for use with Guaranteed consumer applications.  This scaler monitors queue metrics (rates and depths), and scales consumers based on some configured thresholds.

This mash-up of my Partitioned Queues demo + KEDA borrowed significantly from my colleague Dennis' project here, where he went into great detail to explain the Solace PubSub+ scaler: [https://github.com/dennis-brinley/partitioned-queue-demo](https://github.com/dennis-brinley/partitioned-queue-demo).  Please check it out for more detailed information.


## Step 1 - get Terraform
TODO: command to install terraform
```

```


## Step 2 - build Docker container of PQSubscriber


From the "main" / "root" directory of this project:

```
./gradlew clean assemble
docker build -t solace-pqdemo-subscriber:latest --file DockerfileECSDemo .
```

You should now have a Docker image available to be used by the demo:

```
$ docker images | grep solace-pqdemo-subscriber

REPOSITORY                            TAG              IMAGE ID       CREATED          SIZE
solace-pqdemo-subscriber              latest           c37025f6db50   38 minutes ago   665MB
```





## Step 3 - configure subscriber and broker connection info
TODO: update for ECS

TODO: update below
docker build -t your-dockerhub-username/pq-subscriber:latest -f DockerfileECSDemo .
docker push your-dockerhub-username/pq-subscriber:latest


## Step 4 - Deploy PQSubscriber image to ECR
TODO: steps to deploy image to ECR


terraform init
terraform apply \
-var="docker_image=your-dockerhub-username/pq-subscriber:latest" \
-var="solace_host=your-solace-host" \
-var="solace_password=your-password"
## Step 5 - Deploy to ECS via Terraform 

## Step 6 - Get the Solace ECS Scaler
TODO: Steps to get the Solace ECS Scaler and run it


## Step 7 - run the demo

Refer to the README in the parent directory, but essentially:
 - start the Stateful Control app so that each new consumer instance added by the Solace ECS Autoscaler will have the same config
 - start the Order Checker if you want to verify sequencing per-key
 - configure the SLOW subscriber delay to correspond with the `messageReceiveRateTarget` threshold chosen in the Solace scaler (with some wiggle room)
    - e.g. set the target rate to 90 msg/s, but adjust the SLOW subscriber delay to 10 ms to allow for approximately 100 msg/s
 - start the Publisher, increase the rates, watch the scaler do its thing!



## Help




## Tear Down
TODO: steps to teardown terraform deployment
``````

``````



