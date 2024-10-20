# The JavaScript Dashboard

Lots of text here.  TBD.  WIP.

This dashboard visualizes all the various aspects/parts/components of the demo and visualizes them with a sweet GUI.
It leverages:
 - D3.js for real-time visual updates
 - MQTT.js for real-time connectivity to the broker and the demo components (listening to broker events and application "stats" messges)

The demo will run fine as console apps, but this will make it look better.

In order for this to run properly, you will need to:
 - In the broker, in the Message VPN settings, enable "Publish Client Events" and "Publish VPN Events" using MQTT format
 - (If using SEMP) (which makes the demo nicer) In the broker, in the global service settings, for SEMP, enable "Allow Any Host" CORS setting
    - If using Solace Cloud, this may require a Support ticket as they haven't exposed this broker setting yet


- the dashboard uses:
   - MQTT to listen to both broker event logs over the message-bus, as well as "stats" messages published by all the PQ demo components
   - SEMP (both SEMPv1 and SEMPv2) to absolutely hammer the broker with requests to provide a very real-time view of the queue's partitions' depths and rates
   - D3.js to provide nice smooth scrolling numbers and gradient effects for partition depths



## URL Parameters

If you just load the dashboard without any URL params, it will print out a bunch of heler text.  Essentially you need to pass in broker connectivity
information as URL parameters.  At the least, if serving this on `localhost` port 8888:

```
http://localhost:8888?queue=pq12&


```





You need to host this directory with a web server of some sort. I use this basic Python one `http-server`.



## Control Messages

You can use the dashboard to inject control messages to the various publisher and subscriber applications.
This is in addition to publishing them in other ways (e.g. REST POST), or using the StatefulControl application.

Note that in the drop-down for control, you can specify a specific app to receive the control message, rather
than broadcasting to all apps.  This can be very useful to - for example - simulate a single subscriber having 
performance issues and slowing down.  Or configure each subscriber with a slightly different processing speed
and/or ACK delay.



![Dashboard view](https://github.com/SolaceLabs/pq-demo/blob/main/readme/dashboard2.png)




