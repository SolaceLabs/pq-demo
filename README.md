# pq-demo
A demo using JCSMP and MQTT web messaging to show the behaviour of Solace partitioned queues.

## Overview

This demo is meant to demonstrate a number of things:
- Partitioned Queues behaviour, with regards to consumers and rebalancing
- Proving / verifying sequencing remains intact in a variety of scenarios


### Uses / Features

- Guaranteed messaging for all keyed and sequenced data going through the specified queue
- Direct messaging for all stats updates, control messages, and events
- MQTT websockets for the JavaScript dashboard
- 




## The Apps

The demo consists of 5 main components:

- **PQPublisher**: a Guaranteed messaging application that publishes messages containing keys and sequence numbers to a specified topic.  The runner of the demo (YOU) will ensure that the queue used in the demo has the appropriate topic subscription on it.  For example, if publishing on topic `a/b/c`, the queue should have a matching subscription such as `a/>`, `a/*/c`, or `*/b/>`.


- **PQSubscriber**: a Guaranteed messaging application that will bind to the queue name specified on the command line (whether a partitioned queue, exclusive queue, or regular non-exclusive queue), and listen for demo messages specifically from the PQPublisher application.  It keeps track of all keys that it has seen, and all the sequence numbers for those keys and echos an issues to the console and log file.  Both the Subscribers and the OrderChecker (below) use a `Sequencer` class to maintain this infomation.


- **OrderChecker**: a "backend" process that is meant to listen to all of the outputs of the PQSubscribers and
verify the overall/global order of the data being put out.  This is simulating a database or processing gateway, and is to ensure that even during client application failures or consumer scaling, that global/total order is still maintained.
**The biggest/worst issue is if the `Sequencer` sees a gap, where a message on a particular key arrives but the previous sequence number on the same key has not yet.**


- **StatefulControl**: an optional utility that listens to all Control topics and maintains the current demo configuration state.  This allows "late joiners" or applications that connect later to find out the appropriate configuration.


- **The HTML / JS dashboard**: this GUI display provides a real-time view of the queue of interest and any connected clients.  It is


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

All applications are subscribed to the topic `pq-demo/control-all/>`, and will therefore receive any "[broadcast](https://en.wikipedia.org/wiki/Broadcasting_(networking))" Control message sent to all applications.

Each application is also subscribed to a unique Control topic using their name, such as `pq-demo/control-sub-1234/>` or `pq-demo/control-pub-2345/>`.  This allows you to send "[unicast](https://en.wikipedia.org/wiki/Unicast)" Control messages to just a single application.  


### QUIT - Graceful shutdown

Sending this Control message will cause all apps (except for StatefulControl) to begin a graceful shutdown.  This means that applications will have a chance to stop their publisher and consumer objects, acknowledge any outstanding messages, and quit nicely.

*Note*: when using the `ACKD` ACK Delay option for subscribers, the graceful shutdown period will be longer as there is an intentional delay before acknowledging consumed messages.  (In the future, maybe I will make all buffered messages ACK immediately during shutdown).

Pressing `Ctrl+C` on the terminal applications themselves will also initiate a graceful shutdown for that app, as will issuing a SIGTERM to the process.


### KILL - Instantly terminate

Sending this Control message will cause all apps (except for Stateful Control) to immediatiately* terminate.  This means that applications will not stop gracefully, all connections to the Solace broker will immediately close, and any unacknowledged messages will be made available for redelivery.  *Note that if a subscriber is slow (see below) it might not get the Kill message right away.

Pressing `Esc` and then `Enter` on the terminal applications themselves will instantly kill the app.


### STATE - (All) Echo current State

Each running app will echo their current state (configuration) to the console.  This can be useful for verifying things, or for cutting-and-pasting back into another test run with the "force state" command.


### DISP - (All) Change between showing each message or aggregate stats

String "each"|"agg".  Default = "each".  When running the terminal applications, sometimes you want to see *each* individual message echo'ed to the console, but as rates go up you would prefer these statistics *agg*regated.  Each message is always logged to the logging files, which essentially gives you an audit record of everything the Publishers, Subscribers, and OrderChecker have seen.


### PAUSE - (Pub only) Pause publishing

This publisher-only command toggles publishers between sending and not-sending messages.  This can either be broadcast to all publishers using the `control-all` topic, or to individual publishers by specifying their specific name in the Control topic.  In the dashboard, each publisher has a "pause" button.

### RATE - (Pub only) Change message pubish rate

This Command changes how quickly publishing applications send events to the broker, to the queue. Great care was taken with timing each loop and using Java's `Thrad.sleep()` method that has nanosecond accuracy to help ensure a consistent publish rate is achieved.  This isn't always possible due to thread/process scheduling or priority, but the publisher apps are coded to try to maintain a consistent publishrate


### KEYS - (Pub only) Change the number of keys published on

Integer between 1 and 2^31 (~2.1B); or "max".  Default = 8.

Each publisher has a certain number of keys that it is configured to publish on.  In Solace, Partitioned Queues keys are Strings, so the publishers specify their keys as follows: `<2-chars>-<hex-seqNum>` where the first two chars are the last two numbers of their PID, and the PQ key is expressed as a hexidecimal number.  So for example, if a publisher is called `pub-1234`, and its keyspace size is 16 (only one digit in hex), then one of its partition keys could look like `34-e` or `34-0` (for key #14 and #0 respectively).

Adding something unique from the publisher to the key name is in case multiple publishers are sending.


### PROB - (Pub only) Probability that the publisher will resend this particular PQ key at some point

Decimal number between 0 and 1 inclusive, default = 0.  This number is the likelihood that a "follow-on" message (aka a message with the same PQ key but the next sequence number) will be published at some point in the future.  Note that if using a small-ish number of keys, it is quite likely that a resend will occur anyway; only when using a huge keyspace will this ensure the same key is picked again.

Setting to 0 disables all sequenced checks in the Subscribers and the OrderChecker.


### DELAY - (Pub only) Mean time in ms that the publisher will wait before resending

Related to `PROB` above, this is the approximate time the publisher will wait before sending the follow-on message with the same key.  




### SIZE - (Pub only) How big the message is

Integer between 0 and 100,000.  Default = 0.  Completely unused in the demo, this is simply to drive bandwidth rates and broker performance.


### SLOW - (Sub only) Add slow-subscriber millisecond delay to message callback

Integer between 0 and 1,000.  Default = 0.  This will add an approximate slowdown/sleep/pause in milliseconds in the message callback thread of the Subscriber, simulating processing load.  The value is somewhat randomized, utilizing a scaled Poisson distribution.

Note that the Subscriber does not perform any intelligent flow control, and that its ingress buffers ([dispatch notification queue](view-source:https://docs.solace.com/API/API-Developer-Guide/API-Threading.htm#api_threading_1096948177_601598)) can easily become backed-up with messages with an appropriate large delay, and Direct and Guaranteed messages use the same buffers.  For example, a slow subscriber delay of 100ms means that it will take ~25 seconds to clear its entire window's worth of messages (default AD SUB WIN = 255).  So if a control message is sent to a slow subscriber, it make take a few seconds to be processed if it is stuck behind a number of Guaranteed messages that must be "processed" by the app.

!(Receiving messages in JCSMP)[https://docs.solace.com/API/API-Developer-Guide/Images/java_receive_threading_async.jpg]


### ACKD - (Sub only) ACKnowledgement Delay

Integer between 0 and 30,000. Default = 0.  This setting tells the Subscriber to "buffer" the message for some number of milliseconds before a) sending a message to the backend OrderChecker (for global order checking); and b) acknowledging the message back to the broker.  A larger value means that in case of Subscriber failure (or KILL), more messages will have to be redelivered to the next Subscriber that takes over.

Note that when performing a graceful quit, the Subscriber will delay how long it takes to shutdown 



## Apps

This section details the various application components of the demo.

### StatefulControl

This app is not actually necessary to run the demo.  However, it does provide a way for "late joiners" to receive the currently configured state when they connect to the Solace event broker.  Whenever Control messages are sent, apps will receive and act on Commands that they are interested in.  The StatefulControl app maintains these settings and will provide the "current state" for any appications that joins after the Control messages have been sent.

Note that the `QUIT` and `KILL` commands are not acted on by the StatefulControl app.  Only publisher, subscriber, and OrderChecker apps will respond to these.  This lets you terminate an existing setup, and restart the apps with the same state, as they will request the configuration when they start up.



### PQPublisher

The publisher application has the most number of configurable options.  In addition to the various Commands listed above, the publisher apps can startup with a specified `AD_PUB_WINDOW` size.  This SMF parameter is not tunable during runtime and must be specified at startup, hence why this ia a program argument.  The default value for the application is 255 messages (vs. JCSMP default of 1 message).  255 messages allows a high volume/throughput of messages over a longer RTT (Round-Trip Time) link.  Changing to a lower value could/will impact throughput.

The publisher application keeps a list of all keys that it has published on, and the associated sequence numbers with them.


### PQSubscriber




### OrderChecker


## What it looks like

![Dashboard view](https://github.com/SolaceLabs/pq-demo/blob/main/readme/dashboard2.png)

![Terminals view](https://github.com/SolaceLabs/pq-demo/blob/main/readme/terminals2.png)


