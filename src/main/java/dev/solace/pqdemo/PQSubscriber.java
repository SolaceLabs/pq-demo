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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.FlowEvent;
import com.solacesystems.jcsmp.FlowEventArgs;
import com.solacesystems.jcsmp.FlowEventHandler;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPErrorResponseSubcodeEx;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPReconnectEventHandler;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.OperationNotSupportedException;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.SDTException;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.User_Cos;
import com.solacesystems.jcsmp.XMLMessageListener;

public class PQSubscriber extends AbstractParentApp {

	private static final String APP_NAME = PQSubscriber.class.getSimpleName();
	static {
		// the command I care about
		addMyCommands(EnumSet.of(Command.SLOW, Command.ACKD));
	}

	private static volatile int msgRecvCounter = 0;                 // num messages received per sec

	private static FlowReceiver flowQueueReceiver = null;
	static Sequencer sequencer = new Sequencer(false);
	private static volatile ScaledPoisson slowPoissonDist = new ScaledPoisson(10);  // unused initial value

	private static String queueName = null;  // passed from arguments
	private static String queueNameSimple = null;  // used in topic for ACK proc messages (don't want any wacky chars)
	static String currentFlowStatus = FlowEvent.FLOW_INACTIVE.name();  // default starting value

	// remember to add log4j2.xml to your classpath
	private static final Logger logger = LogManager.getLogger();  // log4j2, but could also use SLF4J, JCL, etc.


	/** call this after stateMap has changed */
	static void updateVars(Map<Command,Object> updatedCommandsPrevValues) {
		if (updatedCommandsPrevValues.containsKey(Command.DISP)) {
			sequencer.showEach = stateMap.get(Command.DISP).equals("each");
		}
		if (updatedCommandsPrevValues.containsKey(Command.SLOW)) {
			// this will give us a slightly randomized "slow" processing delay for each message
			if ((Integer)stateMap.get(Command.SLOW) > 0) slowPoissonDist = new ScaledPoisson((Integer)stateMap.get(Command.SLOW));
		}
		if (updatedCommandsPrevValues.containsKey(Command.ACKD)) {
			// no var to change, we just use stateMap value when ACKing
		}
		if (updatedCommandsPrevValues.containsKey(Command.PROB)) {
			if ((Double)stateMap.get(Command.PROB) == 0) {
				logger.info("Message sequencing disabled, removing all known pqKey sequence numbers");
				// concurrency should be fine, on the same thread... triggered by receiving a message
				sequencer.stopCheckingSequenceNums();
			} else {
				sequencer.startCheckingSequenceNums();
			}
		}
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
		sendDirectMsg("pq-demo/stats/" + ((String)session.getProperty(JCSMPProperties.CLIENT_NAME)), jo.toString());
		
//		maxLengthRate = Math.max(maxLengthRate, Integer.toString(msgRecvCounter).length());
		try {
			String logEntry = String.format("(%s) Msgs: %d, gaps: %d, OoS: %d, reD: %d, dupes: %d, newKs: %d",
        			myName, msgRecvCounter, stats.get("gaps"), stats.get("oos"), stats.get("red"), stats.get("dupes"), stats.get("newKs"));
			if (stateMap.get(Command.DISP).equals("agg")) logger.debug(logEntry);
			else logger.trace(logEntry);
		} catch (Exception e) {
			logger.error("Had an issue when trying to print stats!", e);
		}
		msgRecvCounter = 0;
		sequencer.clearStats();
	}

	/** This is the main app.  Use this type of app for receiving Guaranteed messages (e.g. via a queue endpoint). */
	public static void main(String... args) throws JCSMPException, InterruptedException, IOException {
		if (args.length == 1 && args[0].equals("props")) {
			// for running in k8s environment, let's check to see if a property file exists:
			final String PROPERTIES_FILE = "consumer.properties";
	        String configFile = System.getProperty("user.dir") + "/k8s-config/" + PROPERTIES_FILE;		
	        final Properties props = new Properties();
	        try {
	        	logger.info("Trying to load properties file from: " + configFile);
	        	props.load(new FileInputStream(configFile));
	        	logger.info("Success! Loading properties...");
            } catch (Exception e) {
	        	logger.info("Nope, trying to load properties file via ClassLoader ResourceAsStream");
                try {
                    props.load(PQSubscriber.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE));
                    logger.info("Success! Loading properties...");
                } catch (Exception e2) {
                	logger.error("Could not load properties file '" + PROPERTIES_FILE + "'. Verify k8s config. Terminating.");
                	System.exit(1);
                }
            }
	        List<String> newArgs = new ArrayList<>();
	        newArgs.add(props.getProperty("host"));  // might be null
	        newArgs.add(props.getProperty("vpn_name"));
	        newArgs.add(props.getProperty("username"));
	        newArgs.add(props.getProperty("password"));
	        newArgs.add(props.getProperty("queue.name"));
	        if (System.getenv("SUB_ACK_WINDOW_SIZE") != null) {
	        	newArgs.add(System.getenv("SUB_ACK_WINDOW_SIZE"));
	        }
	        args = newArgs.toArray(args);  // fake the command line arguments with my loaded properties values
		} else if (args.length < 5) {  // Check command line arguments
			System.out.printf("Usage: %s <host:port> <message-vpn> <client-username> <password> <queue> [sub-ad-win-size]%n%n", APP_NAME);
			System.exit(-1);
		}
		logger.info(APP_NAME + " initializing...");
		final JCSMPProperties properties = buildProperties(args);
		queueName = args[4];
		queueNameSimple = queueName.replaceAll("[^a-zA-Z0-9\\-]", "_");  // replace any non-alphanumerics to _
		properties.setProperty(JCSMPProperties.PUB_ACK_WINDOW_SIZE, 255);  // open wide up so we don't end up waiting on anything!
		if (args.length > 5) {
			properties.setProperty(JCSMPProperties.SUB_ACK_WINDOW_SIZE, Integer.parseInt(args[5]));  // can't change once we connect
		}  // else default is 255
		// override the reconnect retries... let this app die if it can't connect eventually (hopefully k8s spins up a new one)
        ((JCSMPChannelProperties)properties.getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES)).setReconnectRetries(5);  // don't try forever
		session = JCSMPFactory.onlyInstance().createSession(properties, null, new SimpleSessionEventHandler());
		session.connect();
		updateMyNameAfterConnect("sub-" + queueNameSimple);
		isConnected = true;

		producer = session.getMessageProducer( new JCSMPStreamingPublishCorrelatingEventHandler() {

			@Override
			public void responseReceivedEx(Object arg0) {
				// ignore it for this demo, but would be ACKs for any Guaranteed published message
			}

			@Override
			public void handleErrorEx(Object o, JCSMPException e, long ts) {
				// TODO Auto-generated method stub
				logger.error("*** Received a producer error (NACK): " + o, e);
				// isShutdown = true;  // don't shutdown, in case a NACK or ACL violation, try to keep going
			}
		});

		// setup Consumer callbacks next: anonymous inner-class for Listener async threaded callbacks
		consumer = session.getMessageConsumer(new JCSMPReconnectEventHandler() {
			@Override
			public boolean preReconnect() throws JCSMPException {
				isConnected = false;
				currentFlowStatus = FlowEvent.FLOW_INACTIVE.name();  // force to "off" until we get the update from the FlowListener
				return true;
			}
			@Override
			public void postReconnect() throws JCSMPException {
				isConnected = true;
			}
		}, new XMLMessageListener() {
			@Override
			public void onReceive(BytesXMLMessage message) {
				String topic = message.getDestination().getName();
				if (topic.startsWith("POST/pq-demo/")) {  // gateway mode
					topic = String.join("/", topic.split("/",2)[1]);
				}
				if (topic.equals("pq-demo/state/update")) {
					Map<Command,Object> updated = parseStateUpdateMessage(((TextMessage)message).getText());
					if (!updated.isEmpty()) logger.info("Will be updating these values: " + updated);
					else logger.debug("Received state update message, but ignoring, all values same");
					updateVars(updated);
				} else if (topic.startsWith("pq-demo/control-")) {  // could be broadcast control, or to just me
					//					processControlMessage(topic);
					Map<Command,Object> updatedState = processControlMessage(topic);
					if (updatedState != null) {
						updateVars(updatedState);
					}
				} else if (topic.startsWith("#SYS/LOG")) {
					BrokerLogFileOnly.log(message);
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
		consumer.start();
		
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
					logger.info("### Received a Flow event: " + event.getEvent().name());
					logger.trace(event.toString());
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

		statsThreadPool.scheduleAtFixedRate(() -> {
			if (!isConnected) return;
			publishPrintStats();
		}, 1, 1, TimeUnit.SECONDS);
		
        final Thread shutdownThread = new Thread(new Runnable() {
            public void run() {
            	try {
	                logger.info("Shutdown detected, graceful quitting begins...");
	                isShutdown = true;
	                publishPrintStats();
	        		flowQueueReceiver.stop();  // stop the queue consumer
	        		Thread.sleep(1500 + (Integer)stateMap.get(Command.ACKD));  // ACKs should flush before session ends, but this is me being paranoid
	                publishPrintStats();
	        		isConnected = false;  // shutting down
	        		flowQueueReceiver.close();
	        		removeSubscriptions();
	        		Thread.sleep(1000);
	                publishPrintStats();  // last time
	        		Thread.sleep(100);
	        		statsThreadPool.shutdown();  // stop printing stats
	        		logger.info("Disconnecting session...");
	        		session.closeSession();  // will also close consumer and producer objects
            	} catch (InterruptedException e) {
            		// ignore, quitting!
            	} finally {
            		logger.info("Goodbye!" + CharsetUtils.WAVE);
            	}
            }
        });
        shutdownThread.setName("Shutdown-Hook");
        Runtime.getRuntime().addShutdownHook(shutdownThread);
		
        logger.info(APP_NAME + " connected, and running. Press Ctrl+C to quit, or Esc+ENTER to kill.");

		// Ready to start the application
        injectSubscriptions();
		// tell the broker to start sending messages on this queue receiver
		flowQueueReceiver.start();  // async queue receive working now, so time to wait until done...

		while (!isShutdown) {
            Thread.sleep(50);  // loopy loop
            if (System.in.available() > 0) {
            	BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            	String line = reader.readLine();
            	if ("\033".equals(line)) {  // octal 33 == dec 27, which is the Escape key
            		logger.info("Killing app...");
            		Runtime.getRuntime().halt(0);
            	}
            }
		}
		logger.info("Main thread exiting.");
	}
	
    // used for sending onward processing confirm to the backend
    static void sendGuaranteedProcMsgAndAck(final BytesXMLMessage msgToAck, final String pqKey,  int delayMs) {
    	// just assume that Java handles a delay of 0 as "submit()" not "schedule()"
    	msgSendThreadPool.schedule(new Runnable() {
			@Override
			public void run() {
		    	String topic = String.format("pq-demo/proc/%s/%s/%s/%d/%s",
		    			queueNameSimple, myName, pqKey, msgToAck.getSequenceNumber(), msgToAck.getRedelivered() ? "reD" : "_");
		    	assert producer != null;
				if (producer.isClosed()) {
					// this is bad. maybe we're shutting down?  but can happen if you disable the "send guaranteed messages" in the client-profile and bounce the client
					logger.warn("Producer.isClosed() but trying to send PROC message to topic: " + topic + ". Cannot ACK.  Aborting.");
					return;
				}
				// would probably be good to check if a) our Flow is still active; b) connected; c) etc...
			   	try {
			    	final BytesMessage msg = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
			    	msg.setDeliveryMode(DeliveryMode.PERSISTENT);  // I'm using Guaranteed to send the "processed" msgs to the backend OrderChecker 
			    	msg.setCorrelationKey(topic);
//			    	msg.setCos(User_Cos.USER_COS_2);  // (not needed anymore since persistent) publish all of these at COS2 so COS1 Direct msgs from broker event logs don't get stuck behind
//			    	if (msgToAck.getSenderTimestamp() != null) msg.setSenderTimestamp(msgToAck.getSenderTimestamp());  // set the timestamp of the outbound message
			   		// send proc message first
					producer.send(msg, JCSMPFactory.onlyInstance().createTopic(topic));
					// then ack back to queue
					logger.trace("PROC message sent, now ACKing: " + topic);
					msgToAck.ackMessage();  // should REALLY (in a proper setup) send a Guaranteed message & WAIT for the ACK confirmation before ACKing this message
					// but this is just a demo... probably good enough here
				} catch (JCSMPException e) {
					logger.error("### Could not send message to topic: " + topic + " due to: " + e.toString());
					if (e instanceof JCSMPTransportException) {  // all reconnect attempts failed
						isShutdown = true;  // let's quit; or, could initiate a new connection attempt
					} else if (e instanceof JCSMPErrorResponseException) {  // might have some extra info
						JCSMPErrorResponseException e2 = (JCSMPErrorResponseException)e;
						logger.warn("Specifics: " + JCSMPErrorResponseSubcodeEx.getSubcodeAsString(e2.getSubcodeEx()) + ": " + e2.getResponsePhrase());
					}
				}
			}
    	}, delayMs, TimeUnit.MILLISECONDS);
    }


	////////////////////////////////////////////////////////////////////////////

	/** Very simple static inner class, used for receives messages from Queue Flows. **/
	private static class QueueFlowListener implements XMLMessageListener {

		@Override
		public void onReceive(BytesXMLMessage msg) {
			msgRecvCounter++;
			try {
				String msgTopic = msg.getDestination().getName();
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
					// this can happen when re-running multiple times without restarting the subscriber, so let's ignore
					// otherwise, there's a good chance that this message is already in our "send proc" queue so could probably discard here
					
					// we've seeen this before, so probably have sent to backend/downstream OrderChecker already
					// should really properly check that, it could have got NACKed maybe...
//					if (!msg.getRedelivered()) logger.error("### UH, I got a message that I already know about, but it's not flagged as redelivered!?!?");
					// OK technically, we should REALLY double-check that my previous

					// LATEST, nope: I'm commenting this out... pass the dupes onto the backend OrderChecker
					// but honestly, really, could handle this smarter... de-dupe here in the subscriber, only resend if we know we haven't got an ACK previously
//					msg.ackMessage();  // ideally, Sequencer should keep track of whether each seq num has been ACKed after publishing the "processed" message to the downstream/backend system
//					return;  // end early, don't try processing it and sending it on because we're assuming (only for the demo) that it was successfully published onwards 
				}
				String[] levels = msgTopic.split("/");
				// pq12/pub-8722/a/3/nack
				if (levels.length > 4 && "nack".equals(levels[4])) {
					logger.warn("Detected NACK resend for message on topic: " + msgTopic);
				}

				// now to "process" the message i.e. sleep this thread for a bit to show "slow processing"
				if ((Integer)stateMap.get(Command.SLOW) > 0) {
					try {
						// not best practice to sleep in callback... only reason is b/c we're using JCSMP, and the callback
						// isn't firing on the reactor thread, so it's decoupled (somewhat)
						// definitely NEVER do this in C or C# or if "callback on reactor" in JCSMP
						// this is actually pretty dumb, because Flow events also arrive on this thread, so things get jammed up behind this slow processing
						Thread.sleep(slowPoissonDist.sample());
					} catch (InterruptedException e) {
						// probably terminating the app
						// don't ack the message
						logger.error("Had en exception in the queue's onReceive() handler", e);
					}
				}
				// Messages are removed from the broker queue when the ACK is received.
				// Therefore, DO NOT ACK until all processing/storing of this message is complete.
				// NOTE that messages can be acknowledged from a different thread.
				// ideally, we should do "processing" in a different thread with a LinkedBlockingQueue, and using flow control (e.g. stop()) when the queue is a certain size to prevent getting overloaded
//					String topic = String.format("pq-demo/proc/%s/%s/%s/%d%s",
//							queueNameSimple, myName, pqKey, msgSeqNum, msg.getRedelivered() ? "/red" : "");
//				String topic = String.format("pq-demo/proc/%s/%s/%s/%d",  // don't pass the redelivered flag into the backend anymore, it doesn't care
//						queueNameSimple, myName, pqKey, msgSeqNum);
				sendGuaranteedProcMsgAndAck(msg, pqKey, (Integer)stateMap.get(Command.ACKD));  // ACK from a different thread
				// ideally we send Guaranteed and wait for the ACK to come back before ACKing the original message
				// that would be the PROPER way, but this is just a silly demo
				// msg.ackMessage();  // ACKs are asynchronous, so always a chance for dupes if we crash right here
			} catch (Exception e) {
				logger.error("Had en exception in the queue's onReceive() handler", e);
				// just so we don't blow up the FlowReceiver
			}
		}

		@Override
		public void onException(JCSMPException e) {
			logger.warn("### Queue " + queueName + " Flow handler received exception.  Stopping!!", e);
			if (e instanceof JCSMPTransportException) {  // all reconnect attempts failed
				isShutdown = true;  // let's quit; or, could initiate a new connection attempt
			} else {
				logger.warn("Not a transport exception, but stopping Queue Flow Receiver, so no more messages.");
				logger.warn("Maybe I should try to reopn the Flow?  Or disconnect?  Tell Aaron if you see this message");
				// Generally unrecoverable exception, probably need to recreate and restart the flow
				flowQueueReceiver.close();
				// add logic in main thread to restart FlowReceiver, or can exit the program
			}
		}
	}

		///////////////////////////////////////////////////////

}
