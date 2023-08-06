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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Sequencer {

	enum Status {
		OK,
		JUMP,
		REWIND,
		NO_OC,  // no order checking
		;
	}
	
	static class SequenceInsertStatus {

		final Status status;
		final int numDupes;
		final int expected;
		final boolean diffSub;

		public SequenceInsertStatus(Status status, int numDupes, int expected, boolean diffSub) {
			this.status = status;
			this.numDupes = numDupes;
			this.expected = expected;
			this.diffSub = diffSub;
		}
	}

	
	private class PerQueueSequence {  // inner class
	
		private class PerKeySequence {  // non-static inner-inner class, means we can see the parent
		
			final String myKey;
			private Map<Integer, AtomicInteger> numDupesPerSeqNum = new HashMap<>();  // if a seqNum has no entry, then no messages received yet, if value==0, then a single message has been received
			int lastSeqNumSeen = 0;  // this is not updated until we are exiting the insert() method
			String lastSubSeen = "";
			Set<Integer> missing = null;//new LinkedHashSet<>();
			int missingCountThreshold = Integer.MAX_VALUE;  // if this gets set, then stop trying to print missing to log until it falls below...
		
			/** called when first initializing for this particular PQ key */
			public PerKeySequence(String myKey, int startingSeq, String who) {
				this.myKey = myKey;
				numDupesPerSeqNum.put(startingSeq, new AtomicInteger(0));
				lastSeqNumSeen = startingSeq;
				lastSubSeen = who;
			}
			
			// only called when all logic finished, this updates our data with the latest seq num seen
			// returns the number of messages we've seen on this seq num (including this one)
			private int incNumDeliveriesOnReturn(int seqNum) {
				lastSeqNumSeen = seqNum;
				if (!numDupesPerSeqNum.containsKey(seqNum)) {  // never seen this seqNum before (usual)
					numDupesPerSeqNum.put(seqNum, new AtomicInteger(0));
					return 0;
				}
				return numDupesPerSeqNum.get(seqNum).incrementAndGet();
			}
			
			public boolean hasPrevSeqNum(int seqNum) {
				return seqNum <= 1 || numDupesPerSeqNum.containsKey(seqNum-1);  // at least one delivery on the previous key
			}
			
			public SequenceInsertStatus insert(int seqNum, String who) {
				if (seqNum == 0) {  // ignore!
					lastSeqNumSeen = 0;  // reset
					return new SequenceInsertStatus(Status.NO_OC, 0, 0, false);
				}
				if (isOrderTracker) {  // the OrderChecker tracks missing messages, but the Subscribers do not
					if (missing != null) {  // if it's been initialized, then at least one message on this key has been missing
						missing.remove(seqNum);  // just try to remove, I don't care if we actually contain it or not right now...
						if (missing.isEmpty()) keysWithGaps.remove(myKey);
						else keysWithGaps.add(myKey);
					} else {
						// nothing missing yet, haven't even initialized the Set
					}
				}
				final boolean diffSub = !who.equals(lastSubSeen);  // is this the same guy that was inserting before?
				lastSubSeen = who;  // now update it
				final int expectedSeqNum = lastSeqNumSeen + 1;
				if (seqNum == lastSeqNumSeen + 1) {  // all good I guess!
					return new SequenceInsertStatus(Status.OK, incNumDeliveriesOnReturn(seqNum), expectedSeqNum, diffSub);
				}
				// else something is wrong.  Gap or redelivery?  remember, haven't seen this one before 
				if (seqNum <= lastSeqNumSeen) {  // REWIND hopefully this is a redelivery!  ( == same is a rewind by 1)
					return new SequenceInsertStatus(Status.REWIND, incNumDeliveriesOnReturn(seqNum), expectedSeqNum, diffSub);
				} else {  // JUMP!
					if (isOrderTracker) {
						// make sure that the missing Set has been initialized
						if (missing == null) missing = new LinkedHashSet<>();
						for (int i=lastSeqNumSeen; i<seqNum; i++) {  // we're jumping, so see what we've missed
							if (!numDupesPerSeqNum.containsKey(i)) {  // if we haven't seen at least one message on this seqNum
								missing.add(i);  // brute force, add anyway even if the set already contains it
							}
						}
						if (!missing.isEmpty()) keysWithGaps.add(myKey);
					}
					return new SequenceInsertStatus(Status.JUMP, incNumDeliveriesOnReturn(seqNum), expectedSeqNum, diffSub);
				}
			}
			
			/** Helper method to just print nice ranges of ints: 3,4,5,8,9 -> 3-5, 8-9 */
			private String toStringMissingRanged() {
				if (!isOrderTracker || missing == null || missing.size() == 0) return "";
				// if we've previously set the threshold (say to 10), then return just the count until the # of missing falls below
				if (missing.size() >= missingCountThreshold) return " missing " +  missing.size() + "+ seqNums";
				else missingCountThreshold = Integer.MAX_VALUE;  // else, reset the threshold (or leave it the same)
				List<String> ranges = new ArrayList<>();
				// assuming that my Set of missing is sorted..!  somehow it appears to be?
				int cursor = -1;
				int start = cursor;
				// Even with a very large number of missing, if all contiguous, the # of 'ranges' will be small
				// For non-exclusive queues, this should alternate, which means the count will be very high
				// essentially we're looking for gaps, and ranges, to combine...
				for (int i : missing) {
					if (cursor == -1) {  // the start!
						start = i;
						cursor = i;
						continue;
					}
					if (i == cursor+1) {
						cursor++;
						continue;  // just the next one up
					}
					// else, there's a gap now...
					if (start == cursor) ranges.add(Integer.toString(start));
					else ranges.add(start + "-" + cursor);
					cursor = i;
					start = i;
				}
				// last open range...
				if (start == cursor) ranges.add(Integer.toString(start));
				else ranges.add(start + "-" + cursor);
				if (ranges.size() > 5) {  // too many holes, it's probably a non-excl queue
					missingCountThreshold = missing.size();  // for next time
				}
				return " missing " + missing.size() + ": " + ranges.toString();
			}
		}
		////////////////////////////////  End of inner-inner class
		
		
		// Next inner class, this maintains keys-per-queue

		private final String queue;  // each sub has the queue name in its name now, so this is not as important, was originally in logging statement
	    private Map<String,PerKeySequence> keysToSeqsMap = new HashMap<>();
	    private Set<String> keysWithGaps = new LinkedHashSet<>();
		
		private PerQueueSequence(String queue) {
			this.queue = queue;
		}

		private int getMissingMsgsCount() {
			int missingMgsCount = 0;
    		String[] keys = new String[0];
    		keys = keysWithGaps.toArray(keys);  // hopefully that doesn't blow something up! multi-threaded access, concurrency exceptions..?
    		for (String key : keys) {
    			if (keysToSeqsMap.get(key).missing != null) {
    				missingMgsCount += keysToSeqsMap.get(key).missing.size();
    			}
    		}
			return missingMgsCount;
		}
		
	    boolean dealWith(String sub, String pqKey, int msgSeqNum, boolean redeliveredFlag) {
	    	try {
			    maxLengthKey = Math.max(maxLengthKey, pqKey.length());  // listerally just for spacing on console display
			    maxLengthSeqNo = Math.max(maxLengthSeqNo, Integer.toString(msgSeqNum).length());  // listerally just for spacing on console display
			    String redStr = "";
			    if (redeliveredFlag) {
			    	redStr = CharsetUtils.RED_FLAG;  // red-elivered flag!
			    	red++;  // increment our counter
			    }
			    // this is my fancy log statement print-out
				String inner = String.format("%s key:%%%ds, got:%%%dd, exp:%%%dd, %%3s, prev:%%s  %%s%%s%s%%s%%s",
						isOrderTracker ? "OC: " + sub : sub, maxLengthKey, maxLengthSeqNo, maxLengthSeqNo, redStr);
				// (sub-YYNK) q:nonex, key:EG-43a7b975, exp: 1, got: 1, -- , prev:✔   OK
				// (sub-YYNK) q:nonex, key:EG-43a7b975, exp: 2, got: 3,  +2, prev:❌  JUMP missing 1: [2]
				// (sub-AVQY) q:nonex, key:EG-43a7b975, exp: 4, got: 2,  -2, prev:✔   REWIND ⚠ DIFFSUB
				PerKeySequence perKeySeq = keysToSeqsMap.get(pqKey);  // do we have a sequence for this key?
			    if (isEnabled && perKeySeq != null) {
			    	// we've seen this key before, so let's insert the update and see what happens...
			    	final SequenceInsertStatus seqStatus = perKeySeq.insert(msgSeqNum, sub);
			    	final String dupeCountStr = seqStatus.numDupes > 0 ? seqStatus.numDupes > 1 ? " DUPE x"+ Integer.toString(seqStatus.numDupes) : " DUPE" : "";
			    	final String plusMinusJumpCount = (seqStatus.expected == msgSeqNum ? "---" :
			    			msgSeqNum < seqStatus.expected ?
			    					Integer.toString(msgSeqNum-seqStatus.expected)
			    					: "+" + Integer.toString(msgSeqNum-seqStatus.expected+1));
			    	final boolean prev = perKeySeq.hasPrevSeqNum(msgSeqNum);
			    	if (!prev) gaps++;  // increment the counter
			    	final String prevStr =  msgSeqNum == 0 ? "-" : prev ? CharsetUtils.PREV_YES : CharsetUtils.PREV_NO;
			    	final String diffSubStr = seqStatus.diffSub ? " " + CharsetUtils.WARNING + " DIFFSUB" : "";
			    	if (seqStatus.numDupes > 0) dupes++;  // increment the counter
			    	final String missingStr = perKeySeq.toStringMissingRanged();  // just to print the ranges of seq nums that we're missing
			    	// OK, so stats for red & gaps (prevSeqNum) & dupes all taken care of... just newKs & oos to take care of...
	                final String logEntry = String.format(inner, pqKey, msgSeqNum, seqStatus.expected, plusMinusJumpCount, prevStr, seqStatus.status, diffSubStr, dupeCountStr, missingStr);
	                // I think we should log all redeliveries, always, for visibility
	                // And any non-OK status should be logged, regardless of redelivery flag or not...
			    	// if (redeliveredFlag) {  // ok, how to deal..?
			    		switch (seqStatus.status) {
	                        case NO_OC:
	                        case OK:
	                        if (redeliveredFlag || seqStatus.diffSub) {
	                            logger.info(logEntry);  // log all redeliveries, even if "OK" sequencing, and when sub changes
	                        } else {
	                            if (seqStatus.numDupes > 0) {  // duplicate message, but no redelivered flag?
	                                logger.info(logEntry);  // log it
	                            } else {  // this is the super normal path
	                                if (perKeySeq.missing != null && !perKeySeq.missing.isEmpty()) {  // will always be empty if isOrderTracker==false
	                                    logger.warn(logEntry);  // log it at WARN if there are some missing seqNums on this key
	                                } else if (showEach) {
	                                    logger.debug(logEntry);  // show each message, log at debug
	                                } else {
	                                    logger.trace(logEntry);  // keep it quiet..!
	                                }
	                            }
	                        }
							break;
			    		case JUMP:  // shouldn't jump ahead, unless we're a non-ex queue and redelivering?  Or during NACK retransmission
			    			// JUMP will always at log at >= INFO
			    			oos++;
			    			if (seqStatus.numDupes > 0) {  // have seen this one before
			    				if (prev) {
			    					logger.info(logEntry);
			    				} else {
			    					logger.warn(logEntry);  // gap!
			    				}
			    			} else {  // never seen before
			    				if (prev) {  // at least we've seen the key before this, so maybe not all bad
			    					logger.info(logEntry);
			    				} else {
			    					if (redeliveredFlag || !isOrderTracker) {
			    						logger.warn(logEntry);  // gap! but this is normal for subscribers to see that take over another flow of messages
			    					} else {
			    						logger.error(logEntry);  // gap!
			    					}
			    				}
			    			}
							break;
			    		case REWIND:  // rewinding, but this is a redelivered msg, so is ok?
			    			// REWIND will always at log at >= INFO
							if (prev) {
			    				logger.info(logEntry);
							} else {
								oos++;
								logger.error(logEntry);
							}
							break;
			    		default:
			    			throw new AssertionError();
			    		}
	                    // end "is red flag"
			    		
			//     	} else {  // not redelivered.  This is usual...
			//     		switch (seqStatus.status) {
			//     		case NO_OC:
			//     		case OK:  // this is the normal
			//     			if (seqStatus.numDupes > 0) {  // log it
	        //                     logger.info(logEntry);
			//     			} else {  // this is the super normal path
			//             		if (showEach || !pkSeq.missing.isEmpty()) {  // will always be empty if isOrderTracker==false
	        //                         logger.info(logEntry);  // log it at INFO if disp=each, or there are some missing seqNums on this key
	        //                     } else {
			//     					logger.debug(logEntry);  // keep it quiet..!
	        //                     }
			//     			}
			// 				break;
			//     		case JUMP:  // shouldn't jump ahead, unless we're a non-ex queue and redelivering?  Or during NACK retransmission
			//     			oos++;
			//     			if (seqStatus.numDupes > 0) {
			//     				if (prev) {  // at least we've seen the key before this, so maybe not all bad
			//     					logger.info(logEntry);
			//     				} else {
			//     					logger.warn(logEntry + " GAP?");
			//     				}
			// //        				logger.error(String.format(inner + " but NO red flag!?", pqKey, status.expected, msgSeqNum, plusMinus, prevStr, status.status, delCountStr));
			//     			} else {  // never seen this seq num before
			//     				if (prev) {  // at least we've seen the key before this, so maybe not all bad
			//     					logger.info(logEntry);
			//     				} else {
			//     					logger.error(logEntry + " GAP! BAD!");
	        //                     }
	        //                 }
			// 				break;
			//     		case REWIND:  // rewinding, but has no redelivered flag, so is ok?
			// 				if (prev) {
			//     					logger.info(logEntry);
			// 				} else {
			// 					oos++;
			// 					logger.fatal(logEntry + " HOW!? PUB NACK?");
			// 				}
			// 				break;
			//     		default:
			//     			throw new AssertionError();
			//     		}
			//     	}
			    	return seqStatus.numDupes > 0;
			    } else {  // first time seeing this PQ key, or we're not enabled so who cares
			    	if (msgSeqNum == 0 || !isEnabled) {  // ignore, either msg with no seq num or we're not tracking
			    		if (showEach) logger.debug(String.format(inner, pqKey, 0, msgSeqNum, "---", "-", "NO_OC", "", "", ""));
			    		else logger.trace(String.format(inner, pqKey, 0, msgSeqNum, "-- ", "-", "NO_OC", "", "", ""));
			    	} else {
				    	keysToSeqsMap.put(pqKey, new PerKeySequence(pqKey, msgSeqNum, sub));  // new guy!
				    	totalKeysSeen++;
				    	newKs++;
				    	if (msgSeqNum > 1) {  // first message of this key, but non-zero seq number... no problem
		//		    		oos++;  // actually, don't count this as an OoSeq, as it causes disturbing stats for viewers
				    		logger.info(String.format(inner, pqKey, 1, msgSeqNum, "+"+msgSeqNum, CharsetUtils.WARNING, " FIRST", "", "", ""));
				    	} else if (msgSeqNum == 1) {
				    		if (showEach) logger.debug(String.format(inner, pqKey, 1, msgSeqNum, "---", CharsetUtils.PREV_YES, "OK", "", "", ""));
				    		else logger.trace(String.format(inner, pqKey, 1, msgSeqNum, "-- ", CharsetUtils.PREV_YES, "OK", "", "", ""));
				    	}
			    	}
			    	// end of block meaning we've never seen this before, or not tracking stats, so return false no dupe!
			    	return false;
			    }
	    	} catch (Exception e) {
	    		System.err.println("### Had an issue with an insert..!!!");
	    		logger.error("### Had an issue with an insert..!!!", e);
	    		e.printStackTrace();
	    		System.err.println(sub + ", " + pqKey + ", " + msgSeqNum + ", " + redeliveredFlag);
	    		logger.error(sub + ", " + pqKey + ", " + msgSeqNum + ", " + redeliveredFlag);
	    		return false;  // or throw an exception?  I dunno
	    	}
		}
	}
		
	// end of inner class, per-queue sequence tracking ///////////////////////////////////////////////////////

	
	private Map<String, PerQueueSequence> queuesToPqKeysSeqNumsMap = new HashMap<>();

	private int maxLengthSeqNo = 1;
    private int maxLengthKey = 1;
    volatile boolean showEach = true;  // starting values.  OC and Sub will change this based on DISP value
    private volatile boolean isEnabled = false;  // are we tracking stats?  when PROB > 0, the OC or Sub will enable this

    private volatile int totalKeysSeen = 0;  // rather than querying each map for their size
    
    // stats, cleared each second for rates
    private volatile int newKs = 0;  // this is a new key we haven't seen before
    private volatile int dupes = 0;  // seen this before
    private volatile int red = 0;    // redelivered flag
    private volatile int oos = 0;    // either a rewind or a jump
    private volatile int gaps = 0;   // means there is no previous seq num for this key
    
    private static final Logger logger = LogManager.getLogger(Sequencer.class);
    private final boolean isOrderTracker;  // only backend Order should track missing, b/c subscribers will often have gaps (due to failover, or non-ex queues)
    
    /** Only the OrderChecker tracks missing sequence numbers, because the subscribers could have 1000s with regular non-ex queues */
    public Sequencer(boolean isOrderTracker) {
    	this.isOrderTracker = isOrderTracker;
    }

    void stopCheckingSequenceNums() {  // on all queues?
    	isEnabled = false;
    	queuesToPqKeysSeqNumsMap.clear();  // will end up GC'ing all of the data b/c nothing else is holding onto it
		
		maxLengthKey = 1;
		maxLengthSeqNo = 1;
		totalKeysSeen = 0;
    }
    
    void startCheckingSequenceNums() {
    	isEnabled = true;
    }
    
    Map<String, Integer> getStats() {
    	Map<String, Integer> map = new HashMap<>();
    	map.put("keys", totalKeysSeen);
    	map.put("newKs", newKs);
    	map.put("dupes", dupes);
    	map.put("red", red);
    	map.put("oos", oos);
    	map.put("gaps", gaps);
    	if (isOrderTracker) {
    		// there could be some race conditions here..!  since this would be getting queried from a different thread than is inserting!
    		int missingCount = 0;
    		int keysWithGaps = 0;
    		for (PerQueueSequence q : queuesToPqKeysSeqNumsMap.values()) {
    			missingCount += q.getMissingMsgsCount();
    			keysWithGaps += q.keysWithGaps.size();
    		}
    		map.put("missing", missingCount);
    		map.put("badSeq", keysWithGaps);
    	}
    	return map;
    }

    /** called every second or so when printing stats, these are per-second stats */
    void clearStats() {
    	newKs = 0;
        dupes = 0;
        red = 0;
        oos = 0;
        gaps = 0;
    }
    
    /** returns true if this is a known expected dupe */
    // we should verify the queue name is the same for all messages, in case of collisions..?
    // what if we are monitoring two queues??
    boolean dealWith(String queue, String sub, String pqKey, int msgSeqNum, boolean redeliveredFlag) {
    	if (!queuesToPqKeysSeqNumsMap.containsKey(queue)) {  // first time seeing this queue
    		queuesToPqKeysSeqNumsMap.put(queue, new PerQueueSequence(queue));
    	}
    	return queuesToPqKeysSeqNumsMap.get(queue).dealWith(sub, pqKey, msgSeqNum, redeliveredFlag);

    
    }


}
