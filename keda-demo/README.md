# KEDA Demo Setup


KEDA (Kubernetes Event-Driven Autoscaler) is a component that allows you to scale pods based on some metric(s).  One of the scalers included with it is for Solace PubSub+.  This scaler 




This demo was based on example scaler and test data from my colleague Dennis: https://github.com/dennis-brinley/partitioned-queue-demo


I have modified my PQ Demo to read en


https://keda.sh/

https://keda.sh/docs/2.12/scalers/solace-pub-sub/





## Step 1 - get k8s configured with KEDA


```
helm repo add kedacore https://kedacore.github.io/charts
helm repo update
kubectl create namespace keda
helm install keda kedacore/keda --namespace keda
```


## Step 2 - build Docker container of PQSubscriber


From the "main" / "root" directory of this project:

```
./gradlew clean assemble
docker build -t solace-consumer:latest --file DockerfileKedaDemo .
docker build -t solace-pqdemo-subscriber:latest --file DockerfileKedaDemo .
```

You should now have a Docker image available to be used by the demo:

```
$ docker images | grep solace-pqdemo-subscriber

REPOSITORY                            TAG              IMAGE ID       CREATED          SIZE
solace-pqdemo-subscriber              latest           c37025f6db50   38 minutes ago   665MB
```





## Step 2 - configure subscriber and broker connection info

```
mkdir tmp
cp *.yaml tmp
cd tmp
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





## Step 3 - apply all the config into k8s

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

kubectl get deployments -w

kubectl get deployments

kubectl get pods









## Tear Down


helm uninstall -n keda keda




