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

import java.io.IOException;
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
		addMyCommands(EnumSet.of(Command.DISP));  // everybody
		addMyCommands(EnumSet.of(Command.PAUSE, Command.KEYS, Command.RATE, Command.PROB, Command.DELAY, Command.SIZE));  // publishers
		addMyCommands(EnumSet.of(Command.SLOW, Command.ACKD));  // subscribers
	}

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
	
			System.out.println(APP_NAME + " initializing...");
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
	
	
			logger.info(APP_NAME + " connected, and running. Press [ENTER] to quit.");
			logger.info("Default starting state: " + buildStatePayload());
	
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
					}
					if (topic.equals("pq-demo/state/force")) {
						EnumSet<Command> updated;
						if (message instanceof TextMessage) {
							updated = parseStateUpdateMessage(((TextMessage)message).getText());
						} else if (message instanceof BytesMessage) {
							updated = parseStateUpdateMessage(new String(((BytesMessage)message).getData()));
						} else {  // either Map or Stream message
							return;
						}
						logger.info("These values were forced updated: " + updated);
						if (!updated.isEmpty()) {
							sendDirectMsg("pq-demo/state/update", buildStatePayload());
						}
						if (message.getReplyTo() != null) {  // control message needs reply!  probably due to REST Gateway mode (otherwise it'd just time out)
							// send empty reply
							sendReplyMsg("\n", message);  // add some newlines so the terminal prompt goes back to normal position 
						}
					} else if (topic.startsWith("pq-demo/control-")) {
						logger.info("Control message detected: '" + topic + "'");
						String[] levels = topic.split("/");
						try {
							Command command = Command.valueOf(levels[2].toUpperCase());
							if (stateMap.containsKey(command)) {
								Object value = parseControlMessageValue(levels, command);
								if (value != null) {
									if (stateMap.get(command).equals(value)) {
										logger.info("Same " + command + " value as before");
									} else {
										logger.info("Different value, updating " + command + ": " + stateMap.get(command) + " -> " + value);
										stateMap.put(command, value);
									}
								}  // else value == null, so nothing to do...
							} else {
								logger.info("Ignoring...");
							}
						} catch (IllegalCommandSyntaxException | IllegalControlTopicException e) {
							logger.info("Exception thrown for control message: " + e.getMessage());
						} catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
							logger.info("Exception thrown for control message: " + e.getMessage());
						}
						if (message.getReplyTo() != null) {  // control message needs reply!  probably due to REST Gateway mode (otherwise it'd just time out)
							sendReplyMsg("\n", message);  // add some newlines so the terminal prompt goes back to normal position 
						}
					} else if (topic.equals("pq-demo/state/request")) {  // someone is requesting the current state
						logger.info("Someone is requesting current state: " + buildStatePayload());
						if (message.getReplyTo() != null) {  // req/rep message
							sendReplyMsg(buildStatePayload(), message);
						} else {  // broadcast to all... when would this happen?????  How could a request come in with no reply-to?
							// maybe just published by whoever is running the demo, and wants to reset all apps back to known state?
							//						System.err.println("I AM HERE AND I DONT KNOW HOW THIS IS TRIGGERED, got a request state message with no reply, broadcasting to all...");
							sendDirectMsg("pq-demo/state/update", buildStatePayload());
						}
					} else {
						logger.warn("Received unhandled message on topic: " + message.getDestination().getName());
					}
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
	
			// Ready to start the application, just some subscriptions
			session.addSubscription(JCSMPFactory.onlyInstance().createTopic("pq-demo/control-all/>"));
			session.addSubscription(JCSMPFactory.onlyInstance().createTopic("POST/pq-demo/control-all/>"));
			session.addSubscription(JCSMPFactory.onlyInstance().createTopic("pq-demo/state/>"));
//			session.addSubscription(JCSMPFactory.onlyInstance().createTopic("pq-demo/state/force"));
			session.addSubscription(JCSMPFactory.onlyInstance().createTopic("POST/pq-demo/state/>"));  // REST gateway mode
			consumer.start();  // turn on the subs, and start receiving data
			System.out.printf("%n%s connected and subscribed. Press [ENTER] to quit.%n", APP_NAME);
			sendDirectMsg("pq-demo/state/update", buildStatePayload());  // broadcast my initial state, so any currently running apps get re-synced to my state
	
			while (System.in.available() == 0 && !isShutdown) {
				Thread.sleep(500);
			}
			isShutdown = true;
			isConnected = false;  // shutting down
			session.closeSession();  // will also close producer and consumer objects
			Thread.sleep(500);
			System.out.println("Main thread quitting.");
		} finally {
			System.out.println("Final state: (use topic 'pq-demo/state/force' with this payload to reset) " + buildStatePayload());
		}
	}
}
