# KEDA Demo Setup

![scaler gfx](https://github.com/SolaceLabs/pq-demo/blob/main/readme/scaler.png)

KEDA (Kubernetes Event-Driven Autoscaler) is a Kubernetes Resource that allows you to scale pods/containers/workloads based on some metric(s).  One of the scalers included is for Solace PubSub+, specifically for use with Guaranteed consumer applications.  This scaler monitors queue metrics (rates and depths), and scales consumers based on some configured thresholds.

This mash-up of my Partitioned Queues demo + KEDA borrowed significantly from my colleague Dennis' project here, where he went into great detail to explain the Solace PubSub+ scaler: [https://github.com/dennis-brinley/partitioned-queue-demo](https://github.com/dennis-brinley/partitioned-queue-demo).  Please check it out for more detailed information.


## Step 1 - get Kubernetes

```
$ kubectl version

kubectl controls the Kubernetes cluster manager.

 Find more information at: https://kubernetes.io/docs/reference/kubectl/
<SNIP>
```

👌🏼


## Step 2 - get k8s configured with KEDA

```
helm repo add kedacore https://kedacore.github.io/charts
helm repo update
kubectl create namespace keda
helm install keda kedacore/keda --namespace keda
```


## Step 3 - build Docker container of PQSubscriber


From the "main" / "root" directory of this project:

```
./gradlew clean assemble
docker build -t solace-pqdemo-subscriber:latest --file DockerfileKedaDemo .
```

You should now have a Docker image available to be used by the demo:

```
$ docker images | grep solace-pqdemo-subscriber

REPOSITORY                            TAG              IMAGE ID       CREATED          SIZE
solace-pqdemo-subscriber              latest           c37025f6db50   38 minutes ago   665MB
```





## Step 4 - configure subscriber and broker connection info

```
mkdir crd
cp *.yaml crd
cd crd
```

Edit the `solace-broker-secrets.yaml` file, and update with:
 - SEMP username and password (can be read-only account)

Edit the `solace-example-pq-scaler.yaml` file,
 - SEMP URL
 - Message VPN
 - Queue name
 - Other config as appropriate:
    - `messageCountTarget`: max number of spooled messages per consumer
    - `messageReceiveRateTarget`: max average message rate per consumer
    - `minReplicaCount`: mininum number of queue consumers
    - `maxReplicaCount`: max number of queue consumers (should probably be your # of partitions)


Edit the `solace-subscriber-secrets.yaml` file, and update with:
 - Solace broker URL with SMF messaging port
 - VPN name
 - Client username and password



Optionally edit the `solace-pqdemo-subscriber.yaml` file:
 - Timezone information (only useful if looking at log files from within the pod)
 - Resources: `limits` / `cpu` & `memory`: this is per-pod. If values are too small, you may have performance issues; if values are too large, you may not have enough resources to run.





## Step 5 - apply all the config into k8s

Apply the secrets:
```
kubectl apply -f solace-broker-secrets.yaml
kubectl apply -f solace-subscriber-secrets.yaml
```

Create the consumer:
```
kubectl apply -f solace-pqdemo-subscriber.yaml
```

Create the scaler:
```
kubectl apply -f solace-example-pq-scaler.yaml
```



## Help

```
kubectl get deployments -w
kubectl get pods
```






## Tear Down


helm uninstall -n keda keda




