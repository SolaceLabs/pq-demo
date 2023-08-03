# pq-demo
A demo using JCSMP and MQTT web messaging to show the behaviour of Solace partitioned queues

## Overview

This demo is meant to demonstrate a number of things:
- Partitioned Queues behaviour, with regards to consumers and rebalancing
- Prooving / verifying 




## The Apps

The demo consists of 5 main components:

- PQPublisher: a Guaranteed messaging publishing application that 


- PQSubscriber: 


- OrderChecker: a "backend" process that is meant to listen to all of the outputs of the PQSubscribers and
verify the overall/global order of the data being put out.


- StatefulControl: a utility that 



- The HTML / JS dashboard: this GUI display provides a real-time view of the queue of interest and any connected clients.
It is


## Building

To build the applications, simply run:
```
./gradlew assemble
```
or on Windows Command Prompt:
```
.\gradlew.bat assemble
```

This will create a folder `build/staged` where the required JAR libraries, config files, and start scripts will be located.






## Running




## Topics in Use

While this demo was originally written to just showcase the Guaranteed messaging features of Partitioned Queues, this demo makes extensive use of Solace Direct messaging for communication between applications, for statistics, events, control, and updates.

If you connect a regular Direct subscriber to the Solace event broker, you will 


- `pq-demo/state/request`: when apps connect, they will attempt to request the current state from the StatefulControl app.  This is so late-joiners can be initiazlized with the same values and configuration as its peers.  If the StatefulControl app is not running, then apps will initialize with default values, but still listen to Control message updates from the event broker.
- `pq-demo/state/update`: when the StatefulControl app starts, and if it gets updated, it will publish a message on this topic that all apps are listening to, to "re-sync" their configuration state.
- `pq-demo/state/force`: only the StatefulControl app is listening on this topic, and can be used to inject a specific configuration after terminating/restarting the StatefulControl.  Helpful in testing.  The Stateful control app will echo its last known configuration to the loger when closing, 
- `pq-demo/control-all/<CMD>...`: all apps are listening to the `control-all` topics.  Not every app responds or cares about a specific message though. You will see this in the logging output of the app.  For example: publisher apps don't care about the `SLOW` slow subscriber Command, and subscribers don't care about the `RATE` message publish rate Command.
- `pq-demo/control-<APP_NAME>/<CMD>...`: a "per-client" unicast topic that each app subscribes to, that allows you to send a Command message to _just_ that one application.  For example, simulating a single subscriber going _bad_ by increasing its `SLOW` slow subscriber processing time, or how long it takes to ACKnowledge a message with the `ACKD` Command.
- `pq-demo/stats/...`: once a second, all publisher, subsriber, and OrderChecker apps will broadcast their statistics on these topics.  The JavaScript dashboard listens to these periodic message to update the stats shown on-screen.  At the same time, these statistics are logged.
- `pq-demo/event/...`: when a PQSubscriber hsa a FlowEvent from their queue that they have connected to, they will log it and also publish a message. The JavaScript dashboard listens to these messages to visually update the subscriber's status.
- `pq-demo/proc/<QUEUE_NAME>/<SUB_NAME>/<PQ_KEY>/<SEQ_NUM>`
- `$SYS/LOG/...`: (MQTT) this demo utilizes the Solace feature of publishing the broker's `event.log` as messages.  The JavaScript dashboard listens to these broker messages to visually update the applications' and partitions' statuses.


## Configuration Options

When running the demo, the publisher and subscriber applications will respond to a number of various commands.
These commands can either be injected via messages, or by using the StatefulControl console application.

To see the full list of commands that the applications can listen to, simply run the application called `Command`:

```
$ bin/Command
Shows all Commands that the PQ Demo responds to (using topics)

  QUIT:  Gracefully quit all running apps
         Both,  0 params,  e.g. 'pq-demo/control-all/quit'
  KILL:  Forcefully kill all running apps
         Both,  0 params,  e.g. 'pq-demo/control-all/kill'
  STATE: Have all running apps dump their state configuration
         Both,  0 params,  e.g. 'pq-demo/control-all/state'
  DISP:  Choose Console display type: each message, or aggregate
         Both,  1 param, string [each|agg], default=each,  e.g. 'pq-demo/control-all/disp/agg'
  PAUSE: Pause or unpause publishing temporarily
         Pub,  0 params,  e.g. 'pq-demo/control-all/pause'
  RATE:  Publish messaage rate
         Pub,  1 param, integer [0..10000], default=2,  e.g. 'pq-demo/control-all/rate/300'
  KEYS:  Number of keys available (per-publisher)
         Pub,  1 param, 'max', or integer [1..2147483647], default=8,  e.g. 'pq-demo/control-all/keys/1000, pq-demo/control-all/keys/max'
  PROB:  Probability of "follow-on" message (same key, next seqNum)
         Pub,  1 param, decimal [0..1], default=0.0,  e.g. 'pq-demo/control-all/prob/0.25'
  DELAY: Mean time in ms (scaled Poisson dist) to delay follow-on message
         Pub,  1 param, integer [0..30000], default=1000,  e.g. 'pq-demo/control-all/delay/2000'
  SIZE:  Size in bytes of published messages
         Pub,  1 param, integer [0..100000], default=0,  e.g. 'pq-demo/control-all/size/1024'
  SLOW:  Slow consumer, mean time in ms (scaled Poisson dist) to take to "process" a message
         Sub,  1 param, integer [0..1000], default=0,  e.g. 'pq-demo/control-all/slow/50'
  ACKD:  Exact ACK Delay / Flush time in ms, for simulating batched processing
         Sub,  1 param, integer [0..30000], default=0,  e.g. 'pq-demo/control-all/flush/1000'

Use REST Messaging! E.g. curl -X POST http://localhost:9000/pq-demo/control-all/rate/100
Use per-client topic with "-name" for individual control: 'pq-demo/control-MDwh0VxT7E/slow/10'
Also, can force state with JSON payload:
  curl http://localhost:9000/pq-demo/state/force -d '{"PROB":0.5,"DISP":"agg","RATE":100,"KEYS":256}'
```

This help screen should help you understand the basics of what the demo can do.

### Control messages, and per-client Control



### QUIT - Graceful shutdown

Sending this Control message will cause all apps (except for StatefulControl) to being a graceful shutdown.  




### KILL - Instantly terminate

### STATE - Echo current State


### DISP - Change between "each" and "aggregate"

When running the terminal applications, sometimes you want to see *each* individual message echo'ed to the console, but as rates go up you would prefer these statistics *agg*regated.  


### PAUSE - Pause publishing

This publisher-only command toggles publishers between sending and not-sending messages.  This can either be broadcast to all publishers using the `control-all` topic, or to individual publishers by specifying their specific name in the Control topic.

### RATE - Change message pubish rate

This Command changes how quickly publishing applications send events to the broker, to the queue. Great care was taken with timing each loop and using Java's `Thrad.sleep()` method that has nanosecond accuracy to help ensure a consistent publish rate is achieved.  This isn't always possible due to thread/process scheduling or priority, but the publisher apps are coded to try to maintain a consistent publishrate


### KEYS - Change the number of keys published on

Integer, between 1..2,147,483,648.  Or "max"

Each publisher has a certain number of keys that it is configured to publish on.  In Solace, Partitioned Queues keys are Strings, so the publishers specify their keys as follows: `<2-chars>-<hex-seqNum>` where the first two chars are the first two letters of their chosen name, and the PQ key is expressed as a hexidecimal number.  So for example, if a publisher is called `pub-ABCD`, and its keyspace size is 16 (only one digit in hex), then one of its partition keys could look like `AB-e` or `AB-0` (for key #14 and #0 respectively).




### PROB - Probability that the publisher will resend this particular PQ key at some point


### DELAY - Mean time in ms that the publisher will wait before resending



### SIZE - How big the message is
#### PUB only
Completely unused in the demo, this is simply to drive bandwidht rates and broker performance.  Default = 0.


### SLOW


### ACKD



## Apps

### StateFulControl

This app is not actually necessary to run the demo.  However, it does provide a way for "late joiners" to receive the currently configured state when they connect to the Solace event broker.  

### PQPublisher


### PQSubscriber




### OrderChecker


## What it looks like

![Dashboard view](https://github.com/SolaceLabs/pq-demo/blob/main/readme/dashboard2.png)

![Terminals view](https://github.com/SolaceLabs/pq-demo/blob/main/readme/terminals2.png)


