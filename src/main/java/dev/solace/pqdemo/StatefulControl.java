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
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageListener;


/**
 * This simple introductory sample shows an application that both publishes and subscribes.
 */
public class StatefulControl extends AbstractParentApp {

	private static final String APP_NAME = StatefulControl.class.getSimpleName();

	static {
		addMyCommands(EnumSet.allOf(Command.class));
//		addMyCommands(EnumSet.of(Command.PAUSE, Command.KEYS, Command.RATE, Command.DELAY, Command.SIZE));  // publishers
//		addMyCommands(EnumSet.of(Command.SLOW, Command.ACKD));  // subscribers
	}
	private static final String PROMPT = ": ";
	private static final Logger logger = LogManager.getLogger();  // log4j2, but could also use SLF4J, JCL, etc.


	/** Simple application for doing pub/sub publish-subscribe  
	 * @throws InterruptedException */
	public static void main(String... args) throws JCSMPException, IOException, InterruptedException {
		try {
			if (args.length < 3) {  // Check command line arguments
				System.out.printf("Usage: %s <host:port> <message-vpn> <client-username> [password]%n", APP_NAME);
				System.out.printf("  e.g. %s localhost default default%n%n", APP_NAME);
				System.exit(-1);
			}

			logger.debug(APP_NAME + " initializing...");
			// Build the properties object for initializing the JCSMP Session
			final JCSMPProperties properties = buildProperties(args);
			session = JCSMPFactory.onlyInstance().createSession(properties);
			session.connect();  // connect to the broker
			updateMyNameAfterConnect("state");
			isConnected = true;
	
			/*        
	        // next section tries to browse an LVQ for stored state, we'll finish this later!
	        String queueName = "lvq-pq";
	        // configure the queue API object locally
	        final Queue lvq = JCSMPFactory.onlyInstance().createQueue(queueName);
	        final EndpointProperties ep = new EndpointProperties(EndpointProperties.ACCESSTYPE_EXCLUSIVE, null, EndpointProperties.PERMISSION_CONSUME, 0);
	
	        final BrowserProperties bp = new BrowserProperties();
	        bp.setEndpoint(lvq);
	
	        // Create a Flow be able to bind to and consume messages from the Queue.
	        final ConsumerFlowProperties flow_prop = new ConsumerFlowProperties();
	        flow_prop.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);  // best practice
	        flow_prop.setActiveFlowIndication(true);  // Flow events will advise when 
	
	        logger.info("Attempting to provision queue '" + queueName + "' on the broker.");
	        try {
	        	session.provision(lvq, ep, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
	        	bp.setEndpoint(lvq);
	        	Browser browser = session.createBrowser(bp);
	        	while (browser.hasMore()) {
	        		BytesXMLMessage stateMsg = browser.getNext();
	        	}
	
	            // see bottom of file for QueueFlowListener class, which receives the messages from the queue
	//            flowQueueReceiver = browser.createBFlow( null, flow_prop, null, new FlowEventHandler() {
	//                @Override
	//                public void handleEvent(Object source, FlowEventArgs event) {
	//                    // Flow events are usually: active, reconnecting (i.e. unbound), reconnected, active
	//                	logger.warn("### Received a Flow event: " + event);
	//                    // try disabling and re-enabling the queue to see in action
	//                }
	//            });
	//        } catch (OperationNotSupportedException e) {  // not allowed to do this
	//            throw e;
	        } catch (JCSMPErrorResponseException e) {  // something else went wrong: queue not exist, queue shutdown, etc.
	            logger.error(e);
	            logger.error("*** Could not establish a connection to queue '" + queueName + "'");
	            logger.error("Exiting.");
	            return;
	        }
	        // tell the broker to start sending messages on this queue receiver
	        flowQueueReceiver.start();
	        // async queue receive working now, so time to wait until done...
			 */
	
	
			// setup Producer callbacks config: simple anonymous inner-class for handling publishing events
			producer = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
				// unused in Direct Messaging application, only for Guaranteed/Persistent publishing application
				@Override public void responseReceivedEx(Object key) {
				}
	
				// can be called for ACL violations, connection loss, and Persistent NACKs
				@Override
				public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
					logger.error("### Producer handleErrorEx() callback:", cause);
					if (cause instanceof JCSMPTransportException) {  // unrecoverable, all reconnect attempts failed
						isShutdown = true;
					}
				}
			});
	
			// setup Consumer callbacks next: anonymous inner-class for Listener async threaded callbacks
			consumer = session.getMessageConsumer(new XMLMessageListener() {
				@Override
				public void onReceive(BytesXMLMessage message) {
					String topic = message.getDestination().getName();
					if (topic.startsWith("POST/pq-demo/")) {  // gateway mode
						topic = String.join("/", topic.split("/",2)[1]);
					} else if (topic.startsWith("#SYS/LOG")) {
						BrokerLogFileOnly.log(message);
						return;  // exit early so prompt doesn't get printed
					}
					if (topic.equals("pq-demo/state/force")) {
						String payload;
						if (message instanceof TextMessage) {
							payload = ((TextMessage)message).getText();
						} else if (message instanceof BytesMessage) {
							payload = new String(((BytesMessage)message).getData(), StandardCharsets.UTF_8);
//							updated = parseStateUpdateMessage(new String(((BytesMessage)message).getData(), StandardCharsets.UTF_8));
						} else {  // either Map or Stream message
							logger.warn("Received an unexpected " + message.getClass().getSimpleName() + " message! Ignoring.");
							return;
						}
						EnumSet<Command> updated = parseStateUpdateMessage(payload);
						if (!updated.isEmpty()) logger.info("These values were forced to update: " + updated);
						else logger.info("Nothing to update");
						if (!updated.isEmpty()) {
							sendDirectMsg("pq-demo/state/update", buildStatePayload());  // tell everyone what the updated state is
						}
						if (message.getReplyTo() != null) {  // control message needs reply!  probably due to REST Gateway mode (otherwise it'd just time out)
							// send empty reply
							sendReplyMsg("\n", message);  // add some newlines so the terminal prompt goes back to normal position 
						}
					} else if (topic.startsWith("pq-demo/control-all/")) {
						logger.info("Control message detected: '" + topic + "'");
						String[] levels = topic.split("/");
						try {
							Command command = Command.valueOf(levels[2].toUpperCase());
							handleCommandUpdate(command, levels.length > 3 ? levels[3] : null, false);
						} catch (RuntimeException e) {
							logger.warn("Ignoring! " + e.getMessage());
						}
						if (message.getReplyTo() != null) {  // control message needs reply!  probably due to REST Gateway mode (otherwise it'd just time out)
							sendReplyMsg("\n", message);  // add some newlines so the terminal prompt goes back to normal position 
						}
					} else if (topic.equals("pq-demo/state/request")) {  // someone is requesting the current state
						logger.debug(message.getSenderId() + " is requesting current state: " + buildStatePayload());
						if (message.getReplyTo() != null) {  // req/rep message
							sendReplyMsg(buildStatePayload(), message);
						} else {  // broadcast to all... when would this happen?????  How could a request come in with no reply-to?
							// maybe just published by whoever is running the demo, and wants to reset all apps back to known state?
							//						System.err.println("I AM HERE AND I DONT KNOW HOW THIS IS TRIGGERED, got a request state message with no reply, broadcasting to all...");
							logger.info("Received state request with no replyTo, broadcasting to all: " + buildStatePayload());
							sendDirectMsg("pq-demo/state/update", buildStatePayload());
						}
					} else {
						logger.warn("Received unhandled message on topic: " + message.getDestination().getName());
					}
	            	System.out.print(PROMPT);
				}
	
				@Override
				public void onException(JCSMPException e) {  // uh oh!
					System.out.printf("### MessageListener's onException(): %s%n",e);
					System.out.println("Final state: " + buildStatePayload());
					if (e instanceof JCSMPTransportException) {  // unrecoverable, all reconnect attempts failed
						isShutdown = true;  // let's quit
					}
				}
			});

			final Thread shutdownThread = new Thread(new Runnable() {
	            public void run() {
	            	try {
		                System.out.println("Shutdown detected, graceful quitting begins...");
		    			isShutdown = true;
		    			isConnected = false;  // shutting down
		    			removeSubscriptions();
		    			consumer.stop();  // stop the consumers
		    			Thread.sleep(500);
		    			session.closeSession();  // will also close producer and consumer objects
	            	} catch (InterruptedException e) {
	            		// ignore, quitting!
	            	} finally {
	            		System.out.println("Goodbye!" + CharsetUtils.WAVE);
	            	}
	            }
	        });
	        shutdownThread.setName("Shutdown-Hook");
	        Runtime.getRuntime().addShutdownHook(shutdownThread);
			
			// Ready to start the application, just some subscriptions
	        addCustomSubscription("pq-demo/state/>");  // all state messages
	        addCustomSubscription("POST/pq-demo/state/>");  // all state messages
//	        addCustomSubscription("#SYS/LOG/>");
	        injectSubscriptions();
			consumer.start();  // turn on the subs, and start receiving data
			
	        logger.info(APP_NAME + " connected, and running. Press Ctrl+C to quit, or Esc+ENTER to kill.");
			logger.debug("Default starting state: " + buildStatePayload());
			sendDirectMsg("pq-demo/state/update", buildStatePayload());  // broadcast my initial state, so any currently running apps get re-synced to my state

        	System.out.print(PROMPT);
			while (!isShutdown) {
	            Thread.sleep(100);  // loopy loop
	            if (System.in.available() > 0) {
	            	BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
	            	String line = reader.readLine();
	            	if (line == null || line.isEmpty()) {
//	            		logger.warn("Ignoring!");
		            	System.out.print(PROMPT);
	            		continue;
	            	}
//	            	Configurator.setLevel("com.solacesystems", "debug");
					if ("\033".equals(line)) {  // octal 33 == dec 27, which is the Escape key
	            		System.out.println("Killing app...");
	            		Runtime.getRuntime().halt(0);
	            	} else if ("?".equals(line)) {
	            		Command.printHelp();
		            	System.out.print(PROMPT);
	            		continue;
	            	}
	            	String[] levels = line.split(" ", 2);
	            	if ("force".equalsIgnoreCase(levels[0])) {
	            		if (levels[1].trim().startsWith("'")) {  // user included the single quotes
	            			levels[1] = levels[1].replaceAll("'", "");  // just blank them
	            		}
						EnumSet<Command> updated = parseStateUpdateMessage(levels[1]);
						if (!updated.isEmpty()) {
							logger.info("These values were forced to update: " + updated);
							sendDirectMsg("pq-demo/state/update", buildStatePayload());  // tell everyone what the updated state is
						}
						else logger.info("Nothing to update");
	            		System.out.print(PROMPT);
	            	} else try {  // otherwise hopefully this is a command that I know and care about
	            		Command command = Command.valueOf(levels[0].toUpperCase());
	            		handleCommandUpdate(command, levels.length > 1 ? levels[1] : null, true);
		            	System.out.print(PROMPT);
					} catch (RuntimeException e) {
						logger.warn("Ignoring! " + e.getMessage());
		            	System.out.print(PROMPT);
	            	}
	            }
			}
			System.out.println("Main thread exiting.");
		} finally {
			System.out.println("Final state: (use topic 'pq-demo/state/force' with this payload to reset):");
			System.out.println(" " + buildStatePayload());
		}
	}

	/** sendCtrlMsgToAll for when using the console app and need to broadcast, otherwise this method called via receiving a control message, so don't send another one */
	private static void handleCommandUpdate(Command command, String param, boolean sendCtrlMsgToAll) throws IllegalCommandSyntaxException {
		if (stateMap.containsKey(command)) {
			Object value = parseControlMessageValue(command, param);
			if (value != null) {
				if (stateMap.get(command).equals(value)) {
					logger.debug("Same " + command + " value as before");
				} else {
					logger.info("Different value, updating " + command + ": " + stateMap.get(command) + " -> " + value);
					stateMap.put(command, value);
				}
				if (sendCtrlMsgToAll) {
//						logger.info("Sending state update message: " + buildStatePayload());
//						sendDirectMsg("pq-demo/state/update", buildStatePayload());
					logger.info("Sending control-all message for command " + command);
					sendDirectMsg("pq-demo/control-all/" + command.name() + "/" + stateMap.get(command));
				}
			} else if (sendCtrlMsgToAll) {  // null return value
				// could be STATE and PAUSE and QUIT and KILL
				logger.info("Sending control-all message for command " + command);
				sendDirectMsg("pq-demo/control-all/" + command.name());
			} else {
				logger.debug("Ignoring");
			}
		} else {
			logger.debug("Ignoring! (not watching)");
		}
	}
}
