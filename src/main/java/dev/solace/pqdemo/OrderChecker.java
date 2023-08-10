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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageListener;

public class OrderChecker extends AbstractParentApp {

    private static final String APP_NAME = OrderChecker.class.getSimpleName();
    
    private static volatile int msgRecvCounter = 0;                 // num messages received per sec
    
    static Sequencer sequencer = new Sequencer(true);
    
    // remember to add log4j2.xml to your classpath
    private static final Logger logger = LogManager.getLogger();  // log4j2, but could also use SLF4J, JCL, etc.


	/** call this after stateMap has changed */
	static void updateVars(Map<Command,Object> updatedCommandsPrevValues) {
		if (updatedCommandsPrevValues.containsKey(Command.DISP)) {
			sequencer.showEach = stateMap.get(Command.DISP).equals("each");
		}
		if (updatedCommandsPrevValues.containsKey(Command.PROB)) {
			if ((Double)stateMap.get(Command.PROB) == 0) {
				// this is fine to do here b/c this therad will be called by API thread, same as checking seq numbers
				logger.info("Message sequencing disabled, removing all known pqKey sequence numbers");
				sequencer.stopCheckingSequenceNums();
			} else {
				sequencer.startCheckingSequenceNums();
			}
		}
	}

	static void publishPrintStats() {
    	JSONObject jo = new JSONObject().put("rate", msgRecvCounter);
		Map<String,Integer> stats = sequencer.getStats();
		for (Entry<String,Integer> entry : stats.entrySet()) {
			jo.put(entry.getKey(), entry.getValue());
		}
    	sendDirectMsg("pq-demo/stats/" + ((String)session.getProperty(JCSMPProperties.CLIENT_NAME)), jo.toString());

//		maxLengthRate = Math.max(maxLengthRate, Integer.toString(msgRecvCounter).length());
		try {
			String logEntry = String.format("(%s) Msgs: %d, missing: %d, gaps: %d, oos: %d, red: %d, dupes: %d, newKs: %d",
        			myName, msgRecvCounter, stats.get("missing"), stats.get("gaps"), stats.get("oos"), stats.get("red"), stats.get("dupes"), stats.get("newKs"));
			if ("agg".equals(stateMap.get(Command.DISP))) logger.debug(logEntry);
			else logger.trace(logEntry);
		} catch (Exception e) {
			logger.error("Had an issue when trying to print stats!", e);
		}
        msgRecvCounter = 0;
        sequencer.clearStats();
	}
    
    /** This is the main app.  Use this type of app for receiving Guaranteed messages (e.g. via a queue endpoint). */
    public static void main(String... args) throws JCSMPException, InterruptedException, IOException {
        if (args.length < 3) {  // Check command line arguments
            System.out.printf("Usage: %s <host:port> <message-vpn> <client-username> [password]%n%n", APP_NAME);
            System.exit(-1);
        }
        logger.debug(APP_NAME + " initializing...");

        final JCSMPProperties properties = buildProperties(args);
//		queueName = args[4];  // only needed for received ACK processing messages
//		queueNameSimple = queueName.replaceAll("[^a-zA-Z0-9]", "_");  // replace any non-alphanumerics to _

        session = JCSMPFactory.onlyInstance().createSession(properties, null, new SimpleSessionEventHandler());
        session.connect();
        updateMyNameAfterConnect("oc");
        isConnected = true;
		
        producer = session.getMessageProducer( new JCSMPStreamingPublishCorrelatingEventHandler() {
			
			@Override
			public void responseReceivedEx(Object key) {
				// ignore it for this demo, but would be ACKs for any Guaranteed published message
			}
			
			@Override
			public void handleErrorEx(Object key, JCSMPException cause, long messageId) {
                logger.error("*** Received a producer error: " + key);
//                isShutdown = true;  // don't shutdown, in case a NACK or ACL violation, try to keep going
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
					Map<Command,Object> updated = parseStateUpdateMessage(((TextMessage)message).getText());
					if (!updated.isEmpty()) logger.info("Will be updating these values: " + updated);
					else logger.debug("Received state update message, but ignoring, all values same");
					updateVars(updated);
				} else if (topic.startsWith("pq-demo/control-")) {  // could be broadcast control, or to just me
					Map<Command,Object> updatedCommand = processControlMessage(topic);
					if (updatedCommand != null) {
						updateVars(updatedCommand);
					}
				} else if (topic.startsWith("pq-demo/proc/")) {  // deal with this, even if PROB == 0 and we're not tracking
					dealWithProcMessage(message);
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
        consumer.start();  // turn on the subs, and start receiving data
        
		// send request for state first before adding subscriptions...
        updateVars(sendStateRequest());

        statsThreadPool.scheduleAtFixedRate(() -> {
        	if (!isConnected) return;
        	publishPrintStats();
        }, 1, 1, TimeUnit.SECONDS);
        
        logger.info(APP_NAME + " connected, and running. Press Ctrl+C to quit, or Esc+ENTER to kill.");
        
        // Ready to start the application, just add some subs
        addCustomSubscription("pq-demo/proc/>");  // listen to "processed" msg receipts from subs
        addCustomSubscription("#SYS/LOG/>");  // listen to broker event logs and interleave with our logs
        injectSubscriptions();
        
        final Thread shutdownThread = new Thread(new Runnable() {
            public void run() {
            	try {
	                System.out.println("Shutdown detected, graceful quitting begins...");
	                isShutdown = true;
	                removeSubscriptions();
	                consumer.stop();
	        		Thread.sleep(1000);
	        		isConnected = false;  // shutting down
	        		publishPrintStats();  // one last time
	        		Thread.sleep(100);
	        		statsThreadPool.shutdown();  // stop printing/sending stats
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
        
        while (!isShutdown) {
            Thread.sleep(50);  // loopy loop
            if (System.in.available() > 0) {
            	BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            	String line = reader.readLine();
            	if ("\033".equals(line)) {  // octal 33 == dec 27, which is the Escape key
            		System.out.println("Killing app...");
            		Runtime.getRuntime().halt(0);
            	}
            }
        }
		System.out.println("Main thread exiting.");
    }
    
    static AtomicInteger lastSubNum = new AtomicInteger(0);
    static Map<String, Integer> subNums = new HashMap<>();
    
    /** this helper app will put a bullet in a spaced column to help make it easier to see who sent a message in the logs */
    static String makeColThing(int pos) {
    	StringBuilder sb = new StringBuilder();
    	for (int i=0; i<pos; i++) {
    		sb.append(' ');
    	}
    	sb.append(CharsetUtils.SUB_CHAR);
    	for (int i=pos; i<lastSubNum.get()-1; i++) {
    		sb.append(' ');
    	}
    	return sb.toString();
    }
    
    static void dealWithProcMessage(BytesXMLMessage msg) {
    	msgRecvCounter++;
    	try {
    		// pq-demo/proc/pq3/sub-abc1/AB234/23  ~or~  pq-demo/proc/pq12/sub-eiof/XY456/01/red
    		final String[] levels = msg.getDestination().getName().split("/");
    		String q = levels[2];
    		String sub = levels[3];
//    		if (!subNums.containsKey(sub)) {
//    			subNums.put(sub, lastSubNum.getAndIncrement());
//    		}
            String pqKey = levels[4];
            int msgSeqNum = Integer.parseInt(levels[5]);
            boolean redelivered = levels.length > 6 && levels[6].equals("reD");
            
            sequencer.dealWith(q, sub, pqKey, msgSeqNum, redelivered);
            
            
    	} catch (Exception e) {
    		// just so we don't blow up the FlowReceiver
    		logger.error("caught in processing order", e);
    	}
            
            
        
    }
    
}
