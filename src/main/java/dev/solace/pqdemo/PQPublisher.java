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
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import com.solacesystems.jcsmp.JCSMPErrorResponseSubcodeEx;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProducerEventHandler;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.JCSMPTransportException;
import com.solacesystems.jcsmp.ProducerEventArgs;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessage.MessageUserPropertyConstants;
import com.solacesystems.jcsmp.XMLMessageListener;

public class PQPublisher extends AbstractParentApp {

	private static final String APP_NAME = PQPublisher.class.getSimpleName();
	static {
		// populate stateMap with what I care about
		addMyCommands(EnumSet.of(Command.PAUSE, Command.KEYS, Command.RATE, Command.DELAY, Command.SIZE));
	}

    private static ScheduledExecutorService singleThreadPool = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("Stats-Print"));

	private static volatile String topicString = null;  // passed in from command line

	private static volatile boolean isPaused = false;
	private static volatile ScaledPoisson delayMsecPoissonDist = new ScaledPoisson((Integer)Command.DELAY.defaultVal);  // starting value

	private static Map<Integer, AtomicInteger> allKeysNextSeqNoMap = new HashMap<>();
	private static PriorityQueue<MessageKeyToResend> timeSortedQueueOfResendMsgs = new PriorityQueue<>();  // sorted on timestamp
	private static Map<Integer, MessageKeyToResend> pqkeyResendMsgsMap = new HashMap<>();  // key'd by key
	private static BlockingQueue<MessageKeyToResend> nackedMsgsToRequeue = new ArrayBlockingQueue<>(300);  // max pub win is 255
	
	final static Random r = new Random();

	// remember to add log4j2.xml to your classpath
	private static final Logger logger = LogManager.getLogger();  // log4j2, but could also use SLF4J, JCL, etc.

	private static volatile int msgSentCounter = 0;                   // num messages sent
    private static volatile int msgRateOver100Count = 0;   // switch to disp=agg if rates too high for too long
	private static volatile int msgNackCounter = 0;                   // num NACKs received
//	private static volatile boolean stopThePressesNack = false;
//	private static volatile MessageKeyToResend stopWaitFor = null;

	// these are just for visualization, makes things align better...
	static int maxLengthKey = 1;
	static int maxLengthSeqNo = 1;
	static int maxLengthRate = 1;
	
	// these are flags used when we need to clear some maps/datastructures, signalling from another thread so we don't cause concurrent modifcations
	private static volatile boolean blankTheSeqNosFlag = false;
	private static volatile boolean blankTheResendQ = false;


	private static void queueResendMsg(MessageKeyToResend futureMsg) {
		// need to keep a sorted list of message by time, but also a map of keys in case we happen to pick this key again
		boolean success = timeSortedQueueOfResendMsgs.add(futureMsg);
		assert success;
		Object o = pqkeyResendMsgsMap.put(futureMsg.pqKeyInt, futureMsg);
		assert o == null;
	}

//	private static String buildPartitionKey() {
//		int num = (r.nextInt((Integer)stateMap.get(Command.KEYS)));
//		return nick + "-" + Integer.toString(num, 16);
//		//		return Integer.toString((int)(Math.random() * keySpaceSize));
//		//		return UUID.randomUUID().toString();
//	}

	private static int buildPartitionKey() {
		return r.nextInt((Integer)stateMap.get(Command.KEYS));
//		return nick + "-" + Integer.toString(num, 16);
		//		return Integer.toString((int)(Math.random() * keySpaceSize));
		//		return UUID.randomUUID().toString();
	}

	private static String buildPqKeyString(int pqKey) {
		return nick + "-" + Integer.toHexString(pqKey);
	}

	
	// call this from main thread to avoid any potential threading/concurrency issues accessing shared objects
	private static void blankTheSeqNos() {
		logger.info("Message sequencing disabled, removing all known pqKey sequence numbers");
		assert Thread.currentThread().getName().equals("main");
		timeSortedQueueOfResendMsgs.clear();
		pqkeyResendMsgsMap.clear();
		allKeysNextSeqNoMap.clear();  // blank all the pqKey sequence nos
		maxLengthSeqNo = 1;
		blankTheSeqNosFlag = false;  // task completed, reset the flag
	}
	
	// call this from main thread to avoid any potential threading/concurrency issues accessing shared objects
	private static void blankResendQ() {
		assert Thread.currentThread().getName().equals("main");
		timeSortedQueueOfResendMsgs.clear();
		pqkeyResendMsgsMap.clear();
		blankTheResendQ = false;  // task completed, reset the flag
	}
	

	/** call this after stateMap has changed, called from API callback thread though
	 * @param updated */
	static void updateVars(EnumSet<Command> updated) {
		if (updated.contains(Command.PAUSE)) {
			logger.info((isPaused ? "Unpausing" : "Pausing") + " publishing...");
			isPaused = !isPaused;  // pause/unpause
		}
		if (updated.contains(Command.RATE)) {
			if ((Integer)stateMap.get(Command.RATE) == 0) isPaused = true;  // pause if we set the rate to 0
			else isPaused = false;  // else we are setting it to something > 0, so if we're paused just unpause
		}
		if (updated.contains(Command.PROB)) {
			if ((Double)stateMap.get(Command.PROB) == 0) {
				// this is coming in on the API dispatch thread
				// so rather than clear the Map here, set a flag to blank from main thread
				blankTheSeqNosFlag = true;
			} else {  // changing the probability, let's blank the resendQ
				logger.info("Change in republish probability, blanking the resendQ");
				blankTheResendQ = true;
			}
		}
		if (updated.contains(Command.DELAY)) {
			if ((Integer)stateMap.get(Command.DELAY) != 0) {
				delayMsecPoissonDist = new ScaledPoisson((Integer)stateMap.get(Command.DELAY));
			}
			logger.info("Change in republish delay, blanking the resendQ");
			blankTheResendQ = true;
		}
	}
	
	private static void publishPrintStats() {
		try {
			maxLengthRate = Math.max(maxLengthRate, Integer.toString(msgSentCounter).length());
			String logEntry = String.format("(%s) Msgs: %" + maxLengthRate + "d, resendQ: %d, NACKs: %d  [ks=%s, p=%.2f, d=%d]",
					myName,
					msgSentCounter,
					timeSortedQueueOfResendMsgs.size() + nackedMsgsToRequeue.size(),
					msgNackCounter,
					(Integer)stateMap.get(Command.KEYS) == Integer.MAX_VALUE ? "max" : Integer.toString((Integer)stateMap.get(Command.KEYS)),
					(Double)stateMap.get(Command.PROB),
					(Integer)stateMap.get(Command.DELAY));
			if (stateMap.get(Command.DISP).equals("agg")) logger.debug(logEntry);
			else logger.trace(logEntry);
			JSONObject jo = new JSONObject()
					.put("rate", msgSentCounter)
					.put("keys", stateMap.get(Command.KEYS))
					.put("prob", stateMap.get(Command.PROB))
					.put("delay", stateMap.get(Command.DELAY))
					.put("activeFlow", !producer.isClosed())
					.put("nacks", msgNackCounter)
					.put("paused", isPaused)
					.put("resendQ", timeSortedQueueOfResendMsgs.size() + nackedMsgsToRequeue.size())
					;
			sendDirectMsg("pq-demo/stats/" + ((String)session.getProperty(JCSMPProperties.CLIENT_NAME)), jo.toString());
			if (msgSentCounter > 100 && "each".equals(stateMap.get(Command.DISP))) {
				msgRateOver100Count++;
				if (msgRateOver100Count >= 5) {  // 5 seconds of sustained speed, switch to disp=agg
					logger.warn("Message rate too high, switching to aggregate display");
					stateMap.put(Command.DISP, "agg");
				}
			} else msgRateOver100Count = 0;
			msgSentCounter = 0;
			msgNackCounter = 0;
		} catch (Exception e) {
			logger.error("Had an issue when trying to print stats!", e);
		}
	}

	/** Main. */
	public static void main(String... args) throws JCSMPException, IOException, InterruptedException {
		if (args.length < 5) {  // Check command line arguments
			System.out.printf("Usage: %s <host:port> <message-vpn> <client-username> <password> <topic> [pub-ad-win-size]%n%n", APP_NAME);
			System.exit(-1);
		}
		logger.debug(APP_NAME + " initializing...");

		final JCSMPProperties properties = buildProperties(args);
		if (args.length > 5) {
			properties.setProperty(JCSMPProperties.PUB_ACK_WINDOW_SIZE, Integer.parseInt(args[5]));  // can't change once we connect
		} else {
			properties.setProperty(JCSMPProperties.PUB_ACK_WINDOW_SIZE, 255);  // fast!  might cause a few OoO messages during NACKs
		}
		topicString = args[4];  // no checks! haha
		session = JCSMPFactory.onlyInstance().createSession(properties, null, new SessionEventHandler() {
			@Override
			public void handleEvent(SessionEventArgs event) {  // could be reconnecting, connection lost, etc.
				logger.info("### Received a Session event: " + event);
			}
		});
		session.connect();
		updateMyNameAfterConnect("pub");
		isConnected = true;

		producer = session.getMessageProducer(new PublishCallbackHandler(), new JCSMPProducerEventHandler() {
			@Override
			public void handleEvent(ProducerEventArgs event) {
				// as of JCSMP v10.10, this event only occurs when republishing unACKed messages on an unknown flow (DR failover)
				logger.info("### Received a producer event: " + event);
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
				if (topic.equals("pq-demo/state/update")) {  // sent by StatefulControl when it starts up
					EnumSet<Command> updated = parseStateUpdateMessage(((TextMessage)message).getText());
					if (!updated.isEmpty()) logger.info("Will be updating these values: " + updated);
					else logger.debug("Received state update message, but ignoring, all values same");
					updateVars(updated);
				} else if (topic.startsWith("pq-demo/control-")) {  // could be broadcast control, or to just me
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
				logger.error("### MessageListener's onException()", e);
				if (e instanceof JCSMPTransportException) {  // unrecoverable, all reconnect attempts failed
					isShutdown = true;  // let's quit
				}
			}
		});
		consumer.start();  // turn on the subs, and start receiving data

		// send request for state first before adding subscriptions...
		updateVars(sendStateRequest());

		// Ready to start the application, just subscriptions
		session.addSubscription(JCSMPFactory.onlyInstance().createTopic("pq-demo/state/update"));  // listen to state update messages from StatefulControl
		session.addSubscription(JCSMPFactory.onlyInstance().createTopic("pq-demo/control-all/>"));  // listen to quit control messages
		session.addSubscription(JCSMPFactory.onlyInstance().createTopic("POST/pq-demo/control-all/>"));  // listen to quit control messages in Gateway mode
		session.addSubscription(JCSMPFactory.onlyInstance().createTopic("pq-demo/control-" + myName + "/>"));  // listen to quit control messages
		session.addSubscription(JCSMPFactory.onlyInstance().createTopic("POST/pq-demo/control-" + myName + "/>"));  // listen to quit control messages in Gateway mode
		singleThreadPool.scheduleAtFixedRate(() -> {
			if (!isConnected) return;  // shutting down
			publishPrintStats();
		}, 1, 1, TimeUnit.SECONDS);


        final Thread shutdownThread = new Thread(new Runnable() {
            public void run() {
            	try {
	                System.out.println("Shutdown detected, graceful quitting begins...");
	                isShutdown = true;
	        		consumer.stop();  // stop the consumers
	        		Thread.sleep(1500);
	        		isConnected = false;  // shutting down
	        		publishPrintStats();  // one last time
	        		Thread.sleep(100);
	        		singleThreadPool.shutdown();  // stop printing/sending stats
	        		session.closeSession();  // will close consumer and producer objects
            	} catch (InterruptedException e) {
            		// ignore, quitting!
            	} finally {
            		System.out.println("Goodbye!" + CharsetUtils.WAVE);
            	}
            }
        });
        shutdownThread.setName("Shutdown-Hook");
        Runtime.getRuntime().addShutdownHook(shutdownThread);
		
        logger.info(APP_NAME + " connected, and running. Press Ctrl+C to quit, or Esc+ENTER to kill.");

//		final BytesMessage message = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);  // preallocate
		long now = System.nanoTime();
		long next = now;  // when to publish the next message, based on current rate (first message, send now)
		while (!isShutdown) {  // loop until shutdown flag, or ctrl-c, or "k"ill
			if (blankTheSeqNosFlag) blankTheSeqNos();  // check if we've disabled order/sequence checking
			if (blankTheResendQ) blankResendQ();
			// ok, time to send a message now!
			// advance the 'next' timer for when the _next_ message is supposed to go
			next += 1_000_000_000L / (Integer)stateMap.get(Command.RATE);  // time to send next message in nanos
//			next = System.nanoTime() + (1_000_000_000L / (Integer)stateMap.get(Command.RATE));  // time to send next message in nanos
			if (!isPaused) {  // otherwise, skip this whole block and just sleep later
				final long nanoTime = System.nanoTime();  // the time right now!
				int pqKey = -1;  // Partition Queue key for this message
				int seqNo = -1;  // uninitialized value, will verify this has been set later...
				// default starting sequenced number (if we have keys to "resend" this will increase)
				MessageKeyToResend m = null;
				// first, is there any NACKed messages that I've received that I should send again?  They should go first...
				m = nackedMsgsToRequeue.poll();  // will be null if empty; if not m.wasNack will be true
				if (m == null) {  // ok, no NACKed messages, how about a sequenced message that has lower timestamp?
					// ok, let's see if there are any follow-on sequenced messages ready to be sent
					if (!timeSortedQueueOfResendMsgs.isEmpty() && timeSortedQueueOfResendMsgs.peek().timeToSendNs < nanoTime) {  // this message should be sent by now...
					    m = timeSortedQueueOfResendMsgs.poll();  // pop it off the queue
					    assert m != null;
					    assert pqkeyResendMsgsMap.containsKey(m.pqKeyInt);
					    pqkeyResendMsgsMap.remove(m.pqKeyInt);
					    // even if this key is too big for current keyspace size, send anyway and then force no resend queueing
//					    if (m.seqNo >= (Integer)stateMap.get(Command.KEYS)) {  // this key too big, must have reduced the key space size
//					    	System.out.println("NOPE!  key too big, throwing away");
//					    	// let's throw away this message
//					    	m = null;
//					    } // else, m is now set
					}
				}
				if (m == null) {  // still?  Ok, so no messages to resend, time to make a new one
					// else, nothing is due to be sent yet, or next pq key is out of range, so make a new partition key
					pqKey = buildPartitionKey();
					// but, is this particular key already queued to be sent in the future?
					if (pqkeyResendMsgsMap.containsKey(pqKey)) {
						// this key is already queued to be resent (probably due to small key space size), so just pop it out
						//					MessageToResend m = msgKeysToResendMap.remove(pqKeyString);
						m = pqkeyResendMsgsMap.remove(pqKey);
						assert m != null;
						boolean success = timeSortedQueueOfResendMsgs.remove(m);  // possibly O(n) time
						assert success;
						seqNo = m.seqNo;
					} else {  // got our PQ key, and it's not in the resend queue...
						// need to set the sequence number
						if ((Double)stateMap.get(Command.PROB) > 0) {  //are we tracking sequence numbers on each key?
							// let's check if we've already seen this key before, and need to set the seq num correctly...
							if (allKeysNextSeqNoMap.containsKey(pqKey)) {
								seqNo = allKeysNextSeqNoMap.get(pqKey).get();
							} else {
								seqNo = 1;  // first one
							}
						} else {
							seqNo = 0;  // not tracking
						}
					}
				} else {  // use our resend message, either from a NACK or from teh resend queue
					pqKey = m.pqKeyInt;
					seqNo = m.seqNo;
				}
				assert pqKey >= 0;
				assert seqNo >= 0;
				if ((m == null || !m.wasNack) && (Double)stateMap.get(Command.PROB) > 0) {  // means sequencing is on!
//					allKeysNextSeqNoMap.put(pqKey, seqNo + 1);  // add/overwrite in the next sequence number in the map
					AtomicInteger i = allKeysNextSeqNoMap.get(pqKey);
					if (i == null) allKeysNextSeqNoMap.put(pqKey, new AtomicInteger(seqNo + 1));  // should be putting in 1!
					else i.incrementAndGet();
				}
				final String pqKeyString = buildPqKeyString(pqKey);
	
				// now let's make our SMF message ready for publishing...
//				message.reset();  // ready for reuse
				final BytesMessage message = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);  // preallocate
				message.setDeliveryMode(DeliveryMode.PERSISTENT);  // required for Guaranteed
				message.setApplicationMessageId("aaron rules");
				message.setSequenceNumber(seqNo);  // just use the SMF parameter rather than putting in the payload
				// first, the payload, do we want to set this?
				if ((Integer)stateMap.get(Command.SIZE) > 0) {
		            byte[] payload = new byte[(Integer)stateMap.get(Command.SIZE) ];
		            Arrays.fill(payload, (byte)(r.nextInt(26) + 65));  // some random capital letter
		            message.setData(payload);
				}
				// next the partition key...
				SDTMap map = JCSMPFactory.onlyInstance().createMap();
				map.putString(MessageUserPropertyConstants.QUEUE_PARTITION_KEY, pqKeyString);
				//            map.putString("JMSXGroupID", pqKeyString);   // aka
				map.putLong("origNsTs", nanoTime);  // what time this message was supposed to go out, useful later when resending NACKs
				// not that this is not millis since epoch, this nanoTime() is only useful here in the publisher
				message.setProperties(map);
				message.setSenderTimestamp(System.currentTimeMillis());  // might help later with tracking/tracing/observability
				message.setCorrelationKey(message);  // used for ACK/NACK correlation locally here within the publisher app
				message.setElidingEligible(true);  // why not?
				Topic topic = JCSMPFactory.onlyInstance().createTopic(topicString);
				maxLengthKey = Math.max(maxLengthKey, pqKeyString.length());  // this is just for pretty-printing on console
				maxLengthSeqNo = Math.max(maxLengthSeqNo, Integer.toString(seqNo).length());  // this is just for pretty-printing on console
	
				// if the message I'm sending was a NACK (i.e. I'm trying to republish it) don't do the probability sequence thing...
				if ((m == null || !m.wasNack) && r.nextFloat() < (Double)stateMap.get(Command.PROB) && pqKey < (Integer)stateMap.get(Command.KEYS)) {  // means there will be another message following!\
					// need to "re-queue" this sequenced message later for sending again, the next sequence...
					// don't requeue if the keyspace is smaller now, and this key is too big...
					long msecDelay = 0;  // when?  no delay, send immediately next
					if ((Integer)stateMap.get(Command.DELAY) > 0) msecDelay = delayMsecPoissonDist.sample();  // add some variable delay if specified
					final long timeToSendNext = nanoTime + (msecDelay * 1_000_000L);
					MessageKeyToResend futureMsg = new MessageKeyToResend(pqKey, seqNo + 1, timeToSendNext);
					queueResendMsg(futureMsg);
					String inner = String.format("[%%%ds, %%%dd]", maxLengthKey, maxLengthSeqNo);
					String logEntry = String.format("(%s) Sending [key, seq]: " + inner + ", resend in %dms",
							myName, pqKeyString, seqNo, msecDelay);
					if (stateMap.get(Command.DISP).equals("each")) {
						logger.debug(logEntry);
					} else {
						logger.trace(logEntry);
					}
				} else {
					String inner = String.format("[%%%ds, %%%dd]", maxLengthKey, maxLengthSeqNo);
					String logEntry = String.format("(%s) Sending [key, seq]: " + inner, myName, pqKeyString, seqNo);
					if (stateMap.get(Command.DISP).equals("each")) {
						logger.debug(logEntry);
					} else {
						logger.trace(logEntry);
					}
				}
				try {
					producer.send(message, topic);
					msgSentCounter++;
				} catch (JCSMPException e) {  // threw from send(), only thing that is throwing here, but keep trying (unless shutdown?)
					logger.warn("### Caught while trying to producer.send()",e);
					if (e instanceof JCSMPTransportException) {  // all reconnect attempts failed
						isShutdown = true;  // let's quit; or, could initiate a new connection attempt
					}
				}
            }  // end isPaused
			
			// after all that, now time to calculate how long to sleep before next message to maintain accurate pub rates
			now = System.nanoTime();
			final long delta = next - now;  // is in nanos, need to convert to millis and nanos
			long millis = Math.max(0, (delta) / 1_000_000);  // delta could be < 0 if overdriving rates, loop took too long
			int nanos = Math.max(0, (int)(delta) % 1_000_000);
			Thread.sleep(millis, nanos);
			if (delta < -50_000_000) {  // 50 ms too late, we're too slow!
//				int rate = (int)stateMap.get(Command.RATE);
//				logger.warn("### Publish rate too high, loop taking too long, reducing rate by 1: " + rate + " -> " + (rate-1));
//				stateMap.put(Command.RATE, rate-1);
				next = now;  // reset when we're supposed to send the next message
			}
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

	////////////////////////////////////////////////////////////////////////////

	/** Very simple static inner class, used for handling publish ACKs/NACKs from broker. **/
	private static class PublishCallbackHandler implements JCSMPStreamingPublishCorrelatingEventHandler {

		@Override
		public void responseReceivedEx(Object key) {
			assert key != null;  // this shouldn't happen, this should only get called for an ACK
			assert key instanceof BytesXMLMessage;
//			logger.debug(String.format("ACK for Message %s", key));  // good enough, the broker has it now
//			if (stopThePressesNack) {  // we're blocked waiting for an ACK
//				if (stopWaitFor.sameAs((BytesXMLMessage)key)) {  // found our guy!
//					// all the rest of the messages in the nackedMsg Resend queue should be in proper sorted order
//				}
//			}
		}

		@Override
		public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
			msgNackCounter++;
			if (key != null) {  // NACK
				assert key instanceof BytesXMLMessage;
				MessageKeyToResend msg = new MessageKeyToResend((BytesXMLMessage)key);  // send now (or in the past!)
				// ideally we'd stop all publishing here and wait for this message to get an ACK first
//				if (!stopThePressesNack) {  // first one
//					stopThePressesNack = true;
//					stopWaitFor = msg;  // wait until we get an ACK for this msg before restarting
//				}
//				logger.warn(String.format("NACK for Message %s - %s", key, cause));
				// probably want to do something here.  some error handling possibilities:
				//  - send the message again
				//  - send it somewhere else (error handling queue?)
				//  - log and continue
				//  - pause and retry (backoff) - maybe set a flag to slow down the publisher
				logger.warn("NACK for " + msg + " due to " + cause.getMessage() + ". Requeuing to send NOW.");
				boolean success = nackedMsgsToRequeue.offer(msg);
				assert success;
			} else {  // not a NACK, but some other error (ACL violation for Direct, connection loss, message too big, ...)
				logger.error(String.format("### Producer handleErrorEx() callback: %s%n", cause));
				if (cause instanceof JCSMPTransportException) {  // all reconnect attempts failed
					isShutdown = true;  // let's quit; or, could initiate a new connection attempt
				} else if (cause instanceof JCSMPErrorResponseException) {  // might have some extra info
					JCSMPErrorResponseException e = (JCSMPErrorResponseException)cause;
					logger.warn("Specifics: " + JCSMPErrorResponseSubcodeEx.getSubcodeAsString(e.getSubcodeEx()) + ": " + e.getResponsePhrase());
				}
			}
		}
	}
}
