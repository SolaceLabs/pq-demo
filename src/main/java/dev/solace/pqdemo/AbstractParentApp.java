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

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPErrorResponseSubcodeEx;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPReconnectEventHandler;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.Requestor;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageProducer;

public abstract class AbstractParentApp {
	
	static {  // used by log4j2 for the filename
//		System.setProperty("pid", Long.toString(ProcessHandle.current().pid()));  // ProcessHandle not available in Java 8
		// not actual PID, but a randomish 4-char string
		System.setProperty("pid", String.format("%04x", (int)(Math.random() * 65_536)));
	}
	
    static volatile boolean isShutdown = false;             // are we done?
    static volatile boolean isConnected = false;
    
    static ScheduledExecutorService msgSendThreadPool = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("MessageSender"));
    static ScheduledExecutorService statsThreadPool = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("StatsPrinter"));

    static JCSMPSession session = null;
    static String myName = null;  // will initialize when connecting
    static String nick = "";  // nick 2 (or 3?) letters of myName
    static XMLMessageProducer producer = null;
    static XMLMessageConsumer consumer = null;
    private static Set<String> subscriptions = new LinkedHashSet<>();
    
    static volatile Map<Command,Object> stateMap = new HashMap<>();  // volatile so different threads can see the updates right away
    static {
    	stateMap.put(Command.STATE, Command.STATE.defaultVal);  // everybody reports state
    	stateMap.put(Command.DISP, Command.DISP.defaultVal);  // everybody changes display
    	stateMap.put(Command.QUIT, Command.QUIT.defaultVal);  // everybody can quit
    	stateMap.put(Command.KILL, Command.KILL.defaultVal);  // everybody can die
    	stateMap.put(Command.PROB, Command.PROB.defaultVal);  // everybody listens to the probability value to know if sequence checking is enabled
    }

    /** Helper method for subsclasses to add their interested Commands */
    static void addMyCommands(EnumSet<Command> commands) {
    	for (Command cmd : commands) {
    		stateMap.put(cmd, cmd.defaultVal);
    	}
    }

    private static final Logger logger = LogManager.getLogger(AbstractParentApp.class);  // log4j2, but could also use SLF4J, JCL, etc.

    static class SimpleIsConnectedReconnectHandler implements JCSMPReconnectEventHandler {
		@Override
		public boolean preReconnect() throws JCSMPException {
			isConnected = false;
			return true;
		}
		@Override
		public void postReconnect() throws JCSMPException {
			isConnected = true;
		}
	}
    
    static class SimpleSessionEventHandler implements SessionEventHandler {
        @Override
        public void handleEvent(SessionEventArgs event) {  // could be reconnecting, connection lost, etc.
            logger.warn("### Received a Session event: " + event);
//        	sendDirectMsg("pq-demo/event/" + ((String)session.getProperty(JCSMPProperties.CLIENT_NAME)).replaceAll("/", "_") + "/" + event.getEvent().name());
        	sendDirectMsg("pq-demo/event/" + event.getEvent().name() + "/" + session.getProperty(JCSMPProperties.CLIENT_NAME));
        }
    }
    


    // used for sending onward processing confirm to the backend
/*    static void sendDirect(final String topic, final String payload, int delayMs, DeliveryMode mode) {
    	final TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
    	if (payload != null) msg.setText(payload);
    	msg.setDeliveryMode(mode);  // usually Direct, but I'm using Guaranteed to send the "processed" msgs to the backend OrderChecker 
//    	if (msgToAck.getSenderTimestamp() != null) msg.setSenderTimestamp(msgToAck.getSenderTimestamp());  // set the timestamp of the outbound message

    	// just assume that Java handles a delay of 0 as "submit()" not "schedule()"
    	statsThreadPool.schedule(new Runnable() {
			@Override
			public void run() {
		    	assert producer != null;
				if (producer.isClosed()) {
					// this is bad. maybe we're shutting down?  but can happen if you disable the "send guaranteed messages" in the client-profile and bounce the client
					logger.warn("Producer.isClosed() but trying to send message to topic: " + topic + ".  Aborting.");
					return;
				}
				// would probably be good to check if a) our Flow is still active; b) connected; c) etc...
			   	try {
					producer.send(msg, JCSMPFactory.onlyInstance().createTopic(topic));
					if (msgToAck != null) msgToAck.ackMessage();  // should REALLY (in a proper setup) send a Guaranteed message & WAIT for the ACK confirmation before ACKing this message
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
*/
    
    
    static void sendDirectMsg(final String topic, final String payload) {
//    	sendDirectMsgAndAck(topic, payload, null, 0, DeliveryMode.DIRECT);
    	final TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
    	if (payload != null) msg.setText(payload);

    	msgSendThreadPool.submit(new Runnable() {  // schedule right away
			@Override
			public void run() {
		    	assert producer != null;
				if (producer.isClosed()) {
					// this is bad. maybe we're shutting down?  but can happen if you disable the "send guaranteed messages" in the client-profile and bounce the client
					logger.warn("Producer.isClosed() but trying to send Direct message to topic: " + topic + ".  Aborting.");
					return;
				}
				// would probably be good to check if a) our Flow is still active; b) connected; c) etc...
			   	try {
					producer.send(msg, JCSMPFactory.onlyInstance().createTopic(topic));
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
    	});
    }

    static void sendDirectMsg(String topic) {
    	sendDirectMsg(topic, null);
    }
    
    static void sendReplyMsg(String payload, BytesXMLMessage message) {
    	if (!isConnected) return;
    	assert producer != null;
    	assert !producer.isClosed();
    	TextMessage replyMsg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
    	if (payload != null) replyMsg.setText(payload);
    	try {
			producer.sendReply(message, replyMsg);
		} catch (JCSMPException | IllegalArgumentException e) {  // illegal argument if topic contains empty level
			logger.error("Could not send reply message: " + e.toString());
		}
    }

    static String buildStatePayload() {
		JSONObject jo = new JSONObject();
		for (Entry<Command,Object> entry : stateMap.entrySet()) {
			jo.put(entry.getKey().name(), entry.getValue());  // won't put anything that has 'null' value (e.g. QUIT or KILL)
		}
		return jo.toString();
    }
    
    static Map<Command,Object> sendStateRequest() throws JCSMPException {
        Requestor requestor = session.createRequestor();
        TextMessage reqMsg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        try {
			logger.debug("Requesting state update...");
			BytesXMLMessage response = requestor.request(reqMsg, 1000, JCSMPFactory.onlyInstance().createTopic("pq-demo/state/request"));
			String payload = ((TextMessage)response).getText();
			logger.info("State update received: " + payload);
			Map<Command,Object> updated = parseStateUpdateMessage(payload);
			if (!updated.isEmpty()) logger.info("Will be updating these values: " + updated);
			else logger.debug("Ignoring, all values same");
			return updated;
        } catch (JCSMPException e) {
        	logger.warn("### StatefulControl app not running, no response on 'pq-demo/state/request'");
        	logger.warn("### Will just use default values: " + buildStatePayload());
        	return Collections.emptyMap();
        }
    }
    
    static JCSMPProperties buildProperties(String... args) {
        final JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, args[0]);          // host:port
        properties.setProperty(JCSMPProperties.VPN_NAME,  args[1]);     // message-vpn
        properties.setProperty(JCSMPProperties.USERNAME, args[2]);      // client-username
        if (args.length > 3) properties.setProperty(JCSMPProperties.PASSWORD, args[3]);  // client-password (sometimes optional)
        properties.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);  // subscribe Direct subs after reconnect
        properties.setProperty(JCSMPProperties.NO_LOCAL, true);
        properties.setProperty(JCSMPProperties.GENERATE_SENDER_ID, true);
        JCSMPChannelProperties channelProps = new JCSMPChannelProperties();
        channelProps.setReconnectRetries(20);  // 20 loops, 3 retries, 3 seconds == 180 seconds, or 3 minutes to reconnect
        channelProps.setConnectRetriesPerHost(3);
        properties.setProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES, channelProps);
        return properties;
    }
    
    private static String shortNameGenerator() {
//    	long pid = Long.parseLong(System.getProperty("pid"));  // set at top of this file
//    	return String.format("%04d", pid);
    	return System.getProperty("pid");
    	
/*    	
    	StringBuilder sb = new StringBuilder();
    	for (int i=0; i<4; i++) {
//    		int c = (int)(Math.random() * 62);
//    		if (c < 26) sb.append((char)(65 + c));  // [A-Z]
//    		else if (c < 52) sb.append((char)(97 + c - 26));  // [a-z]
//    		else sb.append((char)(48 + c - 52));  // [0-9]
    		sb.append((char)(65 + (int)(Math.random() * 26)));  // [A-Z]
    	}
    	return sb.toString();
*/
    }
    
    /** type == 'pub' or 'sub' or 'state' or ..?? */
    static void updateMyNameAfterConnect(final String type) throws JCSMPException {
    	String shortName = shortNameGenerator();
//    	nick = shortName.substring(0, 2);
    	nick = shortName.substring(shortName.length()-2);  // last two chars
    	myName = type + "-" + shortName;
//        session.setProperty(JCSMPProperties.CLIENT_NAME, "pq-demo/" + type + "/" + myName);
        session.setProperty(JCSMPProperties.CLIENT_NAME, "pq/" + myName);
//        System.setProperty("log-file-name", "pq_" + type + "_" + myName);
        subscriptions.add("pq-demo/state/update");  // listen to state update messages from StatefulControl
        subscriptions.add("pq-demo/control-all/>");  // control messages to all
        subscriptions.add("POST/pq-demo/control-all/>");
        subscriptions.add("pq-demo/control-" + type + "-all/>");  // control messages to all pubs or subs
        subscriptions.add("POST/pq-demo/control-" + type + "-all/>");
        subscriptions.add("pq-demo/control-" + myName + "/>");  // control messages just to me
        subscriptions.add("POST/pq-demo/control-" + myName + "/>");
    }

    /** Used by subclasses to add more subscriptions.  Call before injecting. */
    static void addCustomSubscription(String sub) {
//		session.addSubscription(JCSMPFactory.onlyInstance().createTopic(sub));
    	subscriptions.add(sub);
    }
    
    /** do this after connecting and you're ready to go */
    static void injectSubscriptions() throws JCSMPException {
    	for (String sub : subscriptions) {
    		session.addSubscription(JCSMPFactory.onlyInstance().createTopic(sub));
    	}
    }
    
    static void removeSubscriptions() {
    	for (String sub : subscriptions) {
    		try {
    			session.removeSubscription(JCSMPFactory.onlyInstance().createTopic(sub));
    		} catch (JCSMPException e) {
    			// ignore, exiting!
    		}
    	}
    }

    
    /** returns the list of commands that were updated, won't return null but an empty Set.
     * Nope, not returns a Map of Updated commands + their previous values. Will return an empty Map if nothing was updated/different. */
    static Map<Command,Object> parseStateUpdateMessage(String jsonPayload) {
    	try {
    		JSONObject jsonStateUpdate = new JSONObject(jsonPayload);  // should have ALL commands/fields from StatefulControl
//    		Set<Command> updatedCommands = new HashSet<>();
    		Map<Command,Object> updatedCommands = new HashMap<>();
    		for (Command c : stateMap.keySet()) {
//    			if (!stateMap.containsKey(c)) {
//    				continue;  // ignore, we don't care about this guy
//    			}
    	    	if (jsonStateUpdate.has(c.name())) {
    	    		switch (c.objectType.getSimpleName()) {
    	    		case "String":
    	    			if (!jsonStateUpdate.getString(c.name()).equalsIgnoreCase((String)stateMap.get(c))) {
    	    				logger.info("Different value, updating " + c + ": " + stateMap.get(c) + " -> " + jsonStateUpdate.getString(c.name()).toLowerCase());
    	    				Object prevVal = stateMap.put(c, jsonStateUpdate.getString(c.name()).toLowerCase());
    	    				assert prevVal != null;
    	    				updatedCommands.put(c,prevVal);
    	    			}
    	    			break;
    	    		case "Integer":
    	    			if (stateMap.get(c) == null || jsonStateUpdate.getInt(c.name()) != (Integer)stateMap.get(c)) {
    	    				logger.info("Different value, updating " + c + ": " + stateMap.get(c) + " -> " + jsonStateUpdate.getInt(c.name()));
    	    				Object prevVal = stateMap.put(c, jsonStateUpdate.getInt(c.name()));
    	    				assert prevVal != null;
    	    				updatedCommands.put(c,prevVal);
    	    			}
    	    			break;
    	    		case "Double":
    	    			if (stateMap.get(c) == null || jsonStateUpdate.getDouble(c.name()) != (Double)stateMap.get(c)) {
    	    				logger.info("Different value, updating " + c + ": " + stateMap.get(c) + " -> " + jsonStateUpdate.getDouble(c.name()));
    	    				Object prevVal = stateMap.put(c, jsonStateUpdate.getDouble(c.name()));
    	    				assert prevVal != null;
    	    				updatedCommands.put(c,prevVal);
    	    			}
    	    			break;
    	    		default:
    	    			logger.warn("Had unepected class type for command " + c);
    	    		}
    	    	} else {
    	    		// ignore this now, because we include QUIT, KILL, STATE as Commands to watch in my StateMap, which wont' be in the update message
//    	    		logger.warn("State message didn't have command " + c + ": " + jsonStateUpdate.toString());
    	    	}
    		}
//    		if (updatedCommands.isEmpty()) return EnumSet.noneOf(Command.class);
//    		return EnumSet.copyOf(updatedCommands);
    		return updatedCommands;
    	} catch (JSONException e) {
    		logger.warn("Invalid JSON Object on state message! " + jsonPayload, e);
//    		return EnumSet.noneOf(Command.class);
    		return Collections.emptyMap();
    	} catch (Exception e) {
    		logger.error("Uncaught exception parsing state message! " + jsonPayload, e);
//    		return EnumSet.noneOf(Command.class);
    		return Collections.emptyMap();
    	}
    }
    
    // only called by the pub/sub apps, not StatefulControl which doesn't use this and handles things itself
    static Map<Command,Object> processControlMessage(String topic) {
		logger.info("Control message detected: '" + topic + "'");
		String[] levels = topic.split("/");
		try {
			Command command = Command.valueOf(levels[2].toUpperCase());
			if (command == Command.QUIT) {
				logger.warn("Graceful Shutdown message received...");
				isShutdown = true;
				return Collections.singletonMap(Command.QUIT, null);
			} else if (command == Command.KILL) {
        		logger.warn("Kill message received!");
        		Runtime.getRuntime().halt(255);  // die immediately
				return Collections.singletonMap(Command.KILL, null);  // unnecessary, but gives a Java compile warning without it
			} else {
				if (stateMap.containsKey(command)) {
					Object value = parseControlMessageValue(command, levels.length > 3 ? levels[3] : null);
					if (value == null) {
						if (command.numParams == 0) {  // expected to be null
							return Collections.singletonMap(command, null);
						} else {  // we have a valid command, but no value associated with it?
							return null;
						}
					} else {
						if (value.equals(stateMap.get(command))) {
							logger.info("Same " + command + " value as before");
							return Collections.singletonMap(command, null);
//							return null;  // ignore this!
						} else {
							logger.info("Different value, updating " + command + ": " + stateMap.get(command) + " -> " + value);
							Object prevVal = stateMap.put(command, value);
							return Collections.singletonMap(command, prevVal);
						}
					}
				} else {
					logger.debug("Ignoring " + command + " message");
					return null;
				}
			}
		} catch (IllegalArgumentException e) {  // bad control topic or syntax
			logger.warn("Ignoring! " + e.getMessage());
			return null;
		}
    }
    
    
    // can only be one "Command" update at a time
    /** returns the Integer, Double, String that is parsed from the control topic. returns null if nothing to parse, no value. */
	static Object parseControlMessageValue(Command command, String param) {
		try {
			switch (command) {
			case QUIT:
				// don't quit the stateful state anymore, leave it up until it's done
				// should probably handle this by each app individually anyway..?
				// StatefulControl will override this in its onMessage() callback
				return null;
			case KILL:
				// don't quit the stateful app anymore either, only pub/sub above in "processMessage()"
        		return null;
			case STATE:
        		logger.info("Current state configuration:\n " + buildStatePayload());
				return null;
			case DISP:
				if ("agg".equalsIgnoreCase(param)) {
					return "agg";
				} else if ("each".equalsIgnoreCase(param)) {
					return "each";
				} else {
					throw new IllegalCommandSyntaxException(command, param);
				}
			case PAUSE:
				return null;
			case PROB:
    			try {
    				double newProb = Double.parseDouble(param);
    				if (newProb < 0 || newProb > 1) {
    					throw new NumberFormatException();
    				}
    				if (newProb > 0 && newProb < 0.001) newProb = 0.001;  // set it to something still visible as: 0.1%
    				return newProb;
    			} catch (NumberFormatException e) {
					throw new IllegalCommandSyntaxException(Command.PROB, param, e);
    			}
			case KEYS:
				try {
    				if ("max".equalsIgnoreCase(param)) {
    					return Integer.MAX_VALUE;
    				} else {
        				Integer newKeyspace = Integer.parseInt(param);
        				if (newKeyspace < 1) {
        					throw new NumberFormatException();
        				}
        				return newKeyspace;
    				}
    			} catch (NumberFormatException e) {
					throw new IllegalCommandSyntaxException(command, param, e);
				}
			case REQD:
			case RATE:
			case SLOW:
            case ACKD:
            case SIZE:
				try {
    				Integer value = Integer.parseInt(param);
    				if (value < command.min || value > command.max) {
    					throw new IllegalCommandSyntaxException(command, param);
    				}
    				return value;
    			} catch (NumberFormatException e) {
					throw new IllegalCommandSyntaxException(command, param, e);
				}
			default:
				// ignore anything else, shouldn't be anything else!!?!?
				throw new IllegalCommandSyntaxException(command, param);
//				logger.error("### UNHANDLED Control topic: " + String.join("/", levels));
//				throw new AssertionError(new IllegalControlTopicException(command, String.join("/", levels)));
			}
		} catch (Exception e) {
			throw new IllegalCommandSyntaxException(command, param, e);
		}
	}
}
