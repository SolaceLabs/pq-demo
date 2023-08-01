/*
 * Copyright 2023 Solace Corporation. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package dev.solace.pqdemo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.FlowEvent;
import com.solacesystems.jcsmp.FlowEventArgs;
import com.solacesystems.jcsmp.FlowEventHandler;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.OperationNotSupportedException;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.SDTException;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageListener;

public class PQSubscriber extends AbstractParentApp {

	private static final String APP_NAME = PQSubscriber.class.getSimpleName();
	static {
		// the command I care about
		addMyCommands(EnumSet.of(Command.STATE, Command.DISP, Command.SLOW, Command.ACKD, Command.PROB));
	}

    private static ScheduledExecutorService singleThreadPool = Executors.newSingleThreadScheduledExecutor(DaemonThreadFactory.INSTANCE.withName("stats-print"));

	private static volatile int msgRecvCounter = 0;                 // num messages received per sec
	// this is just for visualization, makes things align better...
	static int maxLengthRate = 1;

	private static FlowReceiver flowQueueReceiver = null;
	static Sequencer sequencer = new Sequencer(false);
	private static volatile ScaledPoisson slowPoissonDist = new ScaledPoisson(10);  // unused initial value

	private static String queueName = null;  // passed from arguments
	private static String queueNameSimple = null;  // used for ACK proc messages
	static String currentFlowStatus = FlowEvent.FLOW_INACTIVE.name();  // default starting value

	// remember to add log4j2.xml to your classpath
	private static final Logger logger = LogManager.getLogger();  // log4j2, but could also use SLF4J, JCL, etc.


	/** call this after stateMap has changed */
	static void updateVars(EnumSet<Command> updated) {
		if (updated.contains(Command.DISP)) {
			sequencer.showEach = stateMap.get(Command.DISP).equals("each");
		}
		if (updated.contains(Command.SLOW)) {
			// this will give us a slightly randomized "slow" processing delay for each message
			if ((Integer)stateMap.get(Command.SLOW) > 0) slowPoissonDist = new ScaledPoisson((Integer)stateMap.get(Command.SLOW));
		}
		if (updated.contains(Command.ACKD)) {
			// no var to change, we just use stateMap value when ACKing
		}
		if (updated.contains(Command.PROB)) {
			if ((Double)stateMap.get(Command.PROB) == 0) {
				logger.info("Message sequencing disabled, removing all known pqKey sequence numbers");
				// shoud be fine, on the same thread... triggered by receiving a message
				sequencer.stopCheckingSequenceNums();
			} else {
				sequencer.startCheckingSequenceNums();
			}
		}
		//		if (updated.contains(Command.SUBWIN)) {
		//			subAdWinSize = (int)stateMap.get(Command.SUBWIN);
		//			try {
		//				session.setProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE, subAdWinSize);
		//			} catch (JCSMPException e) {
		//				logger.warn("Could not updated subscriber AD window size");
		//			}
		//		}
	}

	private static void publishPrintStats() {
		JSONObject jo = new JSONObject();
		jo.put("rate", msgRecvCounter);
		// sequencer stats..!
		Map<String,Integer> stats = sequencer.getStats();
		for (Entry<String,Integer> entry : stats.entrySet()) {
			jo.put(entry.getKey(), entry.getValue());
		}
		// current config...
		jo.put("slow", (Integer)stateMap.get(Command.SLOW));
		jo.put("ackd", (Integer)stateMap.get(Command.ACKD));
		jo.put("queueName", queueName);
		jo.put("flow", currentFlowStatus);
		sendDirectMsg("pq-demo/stats/sub/" + ((String)session.getProperty(JCSMPProperties.CLIENT_NAME)), jo.toString());
		
		maxLengthRate = Math.max(maxLengthRate, Integer.toString(msgRecvCounter).length());
		try {
			String logEntry = String.format("(%s) Rec'ed msgs/s: %,d, gap: %d, oos: %d, red: %d, dupes: %d, newKs: %d",
        			myName, msgRecvCounter, stats.get("gaps"), stats.get("oos"), stats.get("red"), stats.get("dupes"), stats.get("newKs"));
			if (stateMap.get(Command.DISP).equals("agg")) logger.info(logEntry);
			else logger.debug(logEntry);
		} catch (Exception e) {
			logger.error(e);
		}
		msgRecvCounter = 0;
		sequencer.clearStats();
	}

	/** This is the main app.  Use this type of app for receiving Guaranteed messages (e.g. via a queue endpoint). */
	public static void main(String... args) throws JCSMPException, InterruptedException, IOException {
		if (args.length < 5) {  // Check command line arguments
			System.out.printf("Usage: %s <host:port> <message-vpn> <client-username> <password> <queue> [sub-ad-win-size]%n%n", APP_NAME);
			System.exit(-1);
		}
		logger.info(APP_NAME + " initializing...");

		final JCSMPProperties properties = buildProperties(args);
		queueName = args[4];
		queueNameSimple = queueName.replaceAll("[^a-zA-Z0-9]", "_");  // replace any non-alphanumerics to _
		properties.setProperty(JCSMPProperties.PUB_ACK_WINDOW_SIZE, 255);  // open wide up so we don't end up waiting on anything!
		if (args.length > 5) {
			properties.setProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE, Integer.parseInt(args[5]));  // can't change once we connect
		}  // else default is 255
		// override the reconnect retries... let this app die if it can't connect eventually (hopefully k8s spins up a new one)
        ((JCSMPChannelProperties)properties.getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES)).setReconnectRetries(5);  // don't try forever
		session = JCSMPFactory.onlyInstance().createSession(properties, null, new SimpleSessionEventHandler());
		session.connect();
		updateMyNameAfterConnect("sub");
		isConnected = true;

		producer = session.getMessageProducer( new JCSMPStreamingPublishCorrelatingEventHandler() {

			@Override
			public void responseReceivedEx(Object arg0) {
				// ignore it for this demo, but would be ACKs for any Guaranteed published message
			}

			@Override
			public void handleErrorEx(Object arg0, JCSMPException arg1, long arg2) {
				// TODO Auto-generated method stub
				logger.error("*** Received a producer error: " + arg0);
				// isShutdown = true;  // don't shutdown, in case a NACK or ACL violation, try to keep going
			}
		});

		// setup Consumer callbacks next: anonymous inner-class for Listener async threaded callbacks
		consumer = session.getMessageConsumer(new SimpleIsConnectedReconnectHandler(), new XMLMessageListener() {
			@Override
			public void onReceive(BytesXMLMessage message) {
				String topic = message.getDestination().getName();
				if (topic.startsWith("POST/pq-demo/")) {  // gateway mode
					topic = String.join("/", topic.split("/",2)[1]);
				}
				if (topic.equals("pq-demo/state/update")) {
					EnumSet<Command> updated = parseStateUpdateMessage(((TextMessage)message).getText());
					logger.info("Will be updating these values: " + updated);
					updateVars(updated);
				} else if (topic.startsWith("pq-demo/control-")) {  // could be broadcast control, or to just me
					//					processControlMessage(topic);
					Command updatedState = processControlMessage(topic);
					if (updatedState != null) {
						updateVars(EnumSet.of(updatedState));
					}
				} else {
					logger.warn("Received unhandled message on topic: " + message.getDestination().getName());
				}
				if (message.getReplyTo() != null) {  // probably REST MicroGateway mode
					sendReplyMsg("\n", message);  // add a newline so the terminal prompt goes back to normal position 
				}
			}

			@Override
			public void onException(JCSMPException e) {  // uh oh!
				logger.error("MessageListener's onException()",e);
				if (e instanceof JCSMPTransportException) {  // unrecoverable, all reconnect attempts failed
					isShutdown = true;  // let's quit
				}
			}
		});
		consumer.start();  // turn on the subs, and start receiving data
		
		// send request for state first before adding subscriptions...
		updateVars(sendStateRequest());

		// configure the queue API object locally
		final Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);
		// Create a Flow be able to bind to and consume messages from the Queue.
		final ConsumerFlowProperties flow_prop = new ConsumerFlowProperties();
		flow_prop.setEndpoint(queue);
		flow_prop.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);  // best practice
		flow_prop.setActiveFlowIndication(true);  // Flow events will advise when 

		logger.info("Attempting to bind to queue '" + queueName + "' on the broker.");
		try {
			// see bottom of file for QueueFlowListener class, which receives the messages from the queue
			flowQueueReceiver = session.createFlow(new QueueFlowListener(), flow_prop, null, new FlowEventHandler() {
				@Override
				public void handleEvent(Object source, FlowEventArgs event) {
					// Flow events are usually: active, reconnecting (i.e. unbound), reconnected, active
					logger.warn("### Received a Flow event: " + event);
					currentFlowStatus = event.getEvent().name();
					sendDirectMsg("pq-demo/event/" + event.getEvent().name() + "/" + session.getProperty(JCSMPProperties.CLIENT_NAME));
					// try disabling and re-enabling the queue to see in action
				}
			});
		} catch (OperationNotSupportedException e) {  // not allowed to do this
			throw e;
		} catch (JCSMPErrorResponseException e) {  // something else went wrong: queue not exist, queue shutdown, etc.
			logger.error(e);
			logger.error("*** Could not establish a connection to queue '" + queueName + "'");
			logger.error("Exiting.");
			return;
		}

		singleThreadPool.scheduleAtFixedRate(() -> {
			if (!isConnected) return;
			publishPrintStats();
		}, 1, 1, TimeUnit.SECONDS);
		
        final Thread shutdownThread = new Thread(new Runnable() {
            public void run() {
            	try {
	                System.out.println("Shutdown detected, graceful quitting begins...");
	                logger.warn("Shutdown detected, graceful quitting begins...");
	                isShutdown = true;
	        		flowQueueReceiver.stop();  // stop the queue consumer
//	        		Thread.sleep(1500);  // ACKs should flush before session ends, but this is me being paranoid
	        		Thread.sleep(1000 + (Integer)stateMap.get(Command.ACKD));  // ACKs should flush before session ends, but this is me being paranoid
	                publishPrintStats();
	        		isConnected = false;  // shutting down
	        		flowQueueReceiver.close();
	        		Thread.sleep(1500);
	                publishPrintStats();  // last time
	        		Thread.sleep(100);
	        		singleThreadPool.shutdown();  // stop printing stats
	        		session.closeSession();  // will also close consumer and producer objects
            	} catch (InterruptedException e) {
            		// ignore, quitting!
            	} finally {
            		System.out.println("Goodbye...");
            	}
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdownThread);
		
        logger.info(APP_NAME + " connected, and running. Press Ctrl-C to quit, or [Esc]+[ENTER] to kill.");

		// Ready to start the application
		session.addSubscription(JCSMPFactory.onlyInstance().createTopic("pq-demo/state/update"));  // listen to state update messages from StatefulControl
		session.addSubscription(JCSMPFactory.onlyInstance().createTopic("pq-demo/control-all/>"));
		session.addSubscription(JCSMPFactory.onlyInstance().createTopic("POST/pq-demo/control-all/>"));
		session.addSubscription(JCSMPFactory.onlyInstance().createTopic("pq-demo/control-" + myName + "/>"));  // listen to quit control messages
		session.addSubscription(JCSMPFactory.onlyInstance().createTopic("POST/pq-demo/control-" + myName + "/>"));  // listen to quit control messages in Gateway mode
		// tell the broker to start sending messages on this queue receiver
		flowQueueReceiver.start();  // async queue receive working now, so time to wait until done...

		while (!isShutdown) {
            Thread.sleep(50);  // loopy loop
            if (System.in.available() > 0) {
            	BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            	String line = reader.readLine();
            	if ("".equals(line)) continue;
            	if (line == null || line.charAt(0) == 27) {
            		System.out.println("Killing app...");
            		Runtime.getRuntime().halt(0);
            	} else {
            		System.out.println(Arrays.toString(line.getBytes()));
            	}
            }
		}
		System.out.println("Main thread quitting.");
		logger.warn("Main thread quitting.");
	}


	////////////////////////////////////////////////////////////////////////////

	/** Very simple static inner class, used for receives messages from Queue Flows. **/
	private static class QueueFlowListener implements XMLMessageListener {

		@Override
		public void onReceive(BytesXMLMessage msg) {
			msgRecvCounter++;
			try {
				if (!"aaron rules".equals(msg.getApplicationMessageId())) {
					logger.warn("Ignoring/flushing non-demo message found on queue '" + queueName + "'");
					msg.ackMessage();
					return;
				}
				if (msg.getSequenceNumber() == null || msg.getSequenceNumber() > Integer.MAX_VALUE || msg.getSequenceNumber() < 0) {
					logger.warn("Ignoring/flushing illegal message found with out-of-range sequence number = " + msg.getSequenceNumber());
					msg.ackMessage();
					return;
				}
				int msgSeqNum = msg.getSequenceNumber().intValue();  // better not roll over 2.1B..!
				String pqKey = null;
				try {
					pqKey = msg.getProperties().getString("JMSXGroupID");
				} catch (SDTException e) {
					// ignore
				}
				if (pqKey == null) {  // not great
					logger.warn("Ignoring/flushing illegal message found with no partition pqKey");
					msg.ackMessage();
					return;
				}
				// now track the sequence of this msg...
				boolean knownDupe = sequencer.dealWith(queueNameSimple, myName, pqKey, msgSeqNum, msg.getRedelivered());
				if (knownDupe) {
					// we've seeen this before, so probably have sent to backend/downstream OrderChecker already
					// should really properly check that, it could have got NACKed maybe...
					if (!msg.getRedelivered()) logger.warn("### UH, I got a message that I already know about, but it's not flagged as redelivered!?!?");
					// OK technically, we should REALLY double-check that my previous proc message successfully ACKed by the broker before ACKing this one...
					
					// LATEST, nope: I'm commenting this out... pass the dupes onto the backend OrderChecker
					// but honestly, really, could handle this smarter... de-dupe here in the subscriber, only resend if we know we haven't got an ACK previously
//					msg.ackMessage();  // ideally, Sequencer should keep track of whether each seq num has been ACKed after publishing the "processed" message to the downstream/backend system
//					return;  // end early, don't try processing it and sending it on because we're assuming (only for the demo) that it was successfully published onwards 
				}

				// now to "process" the message i.e. sleep this thread for a bit to show "slow processing"
				if ((Integer)stateMap.get(Command.SLOW) > 0) {
					try {
						// not best practice to sleep in callback... only reason is b/c we're using JCSMP, and the callback
						// isn't firing on the reactor thread, so it's decoupled (somewhat)
						// definitely NEVER do this in C or C# or if "callback on reactor" in JCSMP
						// this is actually pretty dumb, because Flow events also arrive on this thread, so things get jammed up behind this slow processing
						Thread.sleep(slowPoissonDist.sample());

						// Messages are removed from the broker queue when the ACK is received.
						// Therefore, DO NOT ACK until all processing/storing of this message is complete.
						// NOTE that messages can be acknowledged from a different thread.
						// ideally, we should do "processing" in a different thread with a LinkedBlockingQueue, and using flow control (e.g. stop()) when the queue is a certain size to prevent getting overloaded
						String topic = String.format("pq-demo/proc/%s/%s/%s/%d%s",
								queueNameSimple, myName, pqKey, msgSeqNum, msg.getRedelivered() ? "/red" : "");
						sendDirectMsgAndAck(topic, null, msg, (Integer)stateMap.get(Command.ACKD), DeliveryMode.PERSISTENT);  // ACK from a different thread
						// ideally we send Guaranteed and wait for the ACK to come back before ACKing the original message
						// that would be the PROPER way, but this is just a silly demo
						// msg.ackMessage();  // ACKs are asynchronous, so always a chance for dupes if we crash right here
					} catch (InterruptedException e) {
						// probably terminating the app
						// don't ack the message
						logger.error(e);
					}
				} else {
					// msg.ackMessage();  // ACKs are asynchronous, so always a chance for dupes if we crash right here
					String topic = String.format("pq-demo/proc/%s/%s/%s/%d%s",
							queueNameSimple, myName, pqKey, msgSeqNum, msg.getRedelivered() ? "/red" : "");
					sendDirectMsgAndAck(topic, null, msg, (Integer)stateMap.get(Command.ACKD), DeliveryMode.PERSISTENT);
				}
			} catch (Exception e) {
				logger.error(e);
				// just so we don't blow up the FlowReceiver
			}
		}

		@Override
		public void onException(JCSMPException e) {
			logger.warn("### Queue " + queueName + " Flow handler received exception.  Stopping!!", e);
			if (e instanceof JCSMPTransportException) {  // all reconnect attempts failed
				isShutdown = true;  // let's quit; or, could initiate a new connection attempt
			} else {
				// Generally unrecoverable exception, probably need to recreate and restart the flow
				flowQueueReceiver.close();
				// add logic in main thread to restart FlowReceiver, or can exit the program
			}
		}
	}

		///////////////////////////////////////////////////////

}
