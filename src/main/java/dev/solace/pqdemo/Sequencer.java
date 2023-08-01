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
	
	public static class SequenceInsertStatus {

		final Status status;
		final int numDupes;
		final int expected;

		public SequenceInsertStatus(Status status, int numDupes, int expected) {
			this.status = status;
			this.numDupes = numDupes;
			this.expected = expected;
		}
	}

	
	
	
	public class PerKeySequence {  // non-static class, means we can see the parent
	
		final String myKey;
		private Map<Integer, AtomicInteger> numDupes = new HashMap<>();  // <seqNum, numDupes>
		int lastSeqNumSeen = 0;  // this is not updated until we are exiting the insert() method
		Set<Integer> missing = new LinkedHashSet<>();
		int missingCountThreshold = Integer.MAX_VALUE;  // if this gets set, then stop trying to print missing to log until it falls below...
	
		/** called when first initializing for this particular PQ key */
		public PerKeySequence(String myKey, int startingSeq) {
			this.myKey = myKey;
			numDupes.put(startingSeq, new AtomicInteger(0));
			lastSeqNumSeen = startingSeq;
		}
		
		// only called when all logic finished, this updates our data with the latest seq num seen
		// returns the number of messages we've seen on this seq num (including this one)
		private int incNumDeliveriesOnReturn(int seqNum) {
			lastSeqNumSeen = seqNum;
			if (!numDupes.containsKey(seqNum)) {  // never seen this seqNum before (usual)
				numDupes.put(seqNum, new AtomicInteger(0));
				return 0;
			}
			return numDupes.get(seqNum).incrementAndGet();
		}
		
		public boolean hasPrevSeqNum(int seqNum) {
			return seqNum <= 1 || numDupes.containsKey(seqNum-1);  // at least one delivery on the previous key
		}
		
		public SequenceInsertStatus insert(int seqNum) {
			if (seqNum == 0) {  // ignore!
				lastSeqNumSeen = 0;  // reset
				return new SequenceInsertStatus(Status.NO_OC, 0, 0);
			}
			if (trackMissing) {
				missing.remove(seqNum);  // brute force, I don't care if we contain it or not right now...
				if (missing.isEmpty()) keysWithGaps.remove(myKey);
				else keysWithGaps.add(myKey);
			}
			final int expectedSeqNum = lastSeqNumSeen + 1;
			if (seqNum == lastSeqNumSeen + 1) {  // all good I guess!
				return new SequenceInsertStatus(Status.OK, incNumDeliveriesOnReturn(seqNum), expectedSeqNum);
			}
			// else something is wrong.  Gap or redelivery?  remember, haven't seen this one before 
			if (seqNum <= lastSeqNumSeen) {  // REWIND hopefully this is a redelivery!  ( == same is a rewind by 1)
				return new SequenceInsertStatus(Status.REWIND, incNumDeliveriesOnReturn(seqNum), expectedSeqNum);
			} else {  // JUMP!
				if (trackMissing) {
					for (int i=lastSeqNumSeen; i<seqNum; i++) {  // we're jumping, so see what we've missed
						if (!numDupes.containsKey(i)) {
							missing.add(i);  // brute force, add anyway even if the set already contains it
						}
					}
					if (!missing.isEmpty()) keysWithGaps.add(myKey);
				}
				return new SequenceInsertStatus(Status.JUMP, incNumDeliveriesOnReturn(seqNum), expectedSeqNum);
			}
		}
		
		/** Helper method to just print nice ranges of ints: 3,4,5,8,9 -> 3-5, 8-9 */
		private String toStringMissingRanged() {
			if (!trackMissing || missing.size() == 0) return "";
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
	////////////////////////////////  End of inner class
	
    private Map<String,PerKeySequence> keysToSeqsMap = new HashMap<>();
    private Set<String> keysWithGaps = new LinkedHashSet<>();
    private int maxLengthSeqNo = 1;
    private int maxLengthKey = 1;
    volatile boolean showEach = true;  // starting values
    private volatile boolean isEnabled = false;

    // stats
    private volatile int newKs = 0;  // this is a new key we haven't seen before 
    private volatile int dupes = 0;  // seen this before
    private volatile int red = 0;    // redelivered flag
    private volatile int oos = 0;    // either a rewind or a jump
    private volatile int gaps = 0;   // means there is no previous seq num for this key
    
    private static final Logger logger = LogManager.getLogger(Sequencer.class);
    private final boolean trackMissing;  // only backend Order should track missing, b/c subscribers will often have gaps
    
    /** Only the OrderChecker tracks missing sequence numbers, because the subscribers could have 1000s with regular non-ex queues */
    public Sequencer(boolean trackMissing) {
    	this.trackMissing = trackMissing;
    }

    void stopCheckingSequenceNums() {
    	isEnabled = false;
		keysToSeqsMap.clear();
		keysWithGaps.clear();
		maxLengthKey = 1;
		maxLengthSeqNo = 1;
    }
    
    void startCheckingSequenceNums() {
    	isEnabled = true;
    }
    
    Map<String, Integer> getStats() {
    	Map<String, Integer> map = new HashMap<>();
    	map.put("keys", keysToSeqsMap.size());
    	map.put("newKs", newKs);
    	map.put("dupes", dupes);
    	map.put("red", red);
    	map.put("oos", oos);
    	map.put("gaps", gaps);
    	if (trackMissing) {
    		// there could be some race conditions here..!
    		int missingCount = 0;
    		String[] keys = new String[0];
    		keys = keysWithGaps.toArray(keys);  // hopefully that doesn't blow something up!
    		for (String key : keys) {
    			missingCount += keysToSeqsMap.get(key).missing.size();
    		}
    		map.put("missing", missingCount);
    		map.put("badSeq", keysWithGaps.size());
    	}
    	return map;
    }
    
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
    	try {
		    maxLengthKey = Math.max(maxLengthKey, pqKey.length());  // listerally just for spacing on console display
		    maxLengthSeqNo = Math.max(maxLengthSeqNo, Integer.toString(msgSeqNum).length());  // listerally just for spacing on console display
		
		    String redStr = "";
		    if (redeliveredFlag) {
		    	redStr = CharsetUtils.RED_FLAG;  // red-elivered flag!
		    	red++;  // increment our counter
		    }
		    // this is my fancy log statement print-out
			String inner = String.format("(%s) key:%%%ds, exp:%%%dd, got:%%%dd, %%3s, prv:%%s  %%s%s%%s%%s",
					sub, /* makeColThing(subNums.get(sub)), */ 
					maxLengthKey, maxLengthSeqNo, maxLengthSeqNo, redStr);
			PerKeySequence pkSeq = keysToSeqsMap.get(pqKey);  // do we have a sequence for this key?
		    if (isEnabled && pkSeq != null) {
		    	// we've seen this key before, so let's insert the update and see what happens...
		    	final SequenceInsertStatus seqStatus = pkSeq.insert(msgSeqNum);
		    	final String dupeCountStr = seqStatus.numDupes > 0 ? seqStatus.numDupes > 1 ? " DUPE x"+ Integer.toString(seqStatus.numDupes) : " DUPE" : "";
		    	final String plusMinus = (seqStatus.expected == msgSeqNum ? "---" :
		    		msgSeqNum < seqStatus.expected ? Integer.toString(msgSeqNum-seqStatus.expected) : "+" + Integer.toString(msgSeqNum-seqStatus.expected+1));
		    	final boolean prev = pkSeq.hasPrevSeqNum(msgSeqNum);
		    	if (!prev) gaps++;  // increment the counter
		    	final String prevStr =  msgSeqNum == 0 ? "-" : prev ? CharsetUtils.PREV_YES : CharsetUtils.PREV_NO;
		    	if (seqStatus.numDupes > 0) dupes++;  // increment the counter
		    	final String missingStr = pkSeq.toStringMissingRanged();  // just to print the ranges of seq nums that we're missing
		    	// OK, so stats for red & gaps (prevSeqNum) & dupes all taken care of... just newKs & oos to take care of...
		    	// all these stupid logging statements are almost identical!  I should simplify...
		    	if (redeliveredFlag) {  // ok, how to deal..?
		    		// I think we should log all redeliveries, always, for visibility
		    		switch (seqStatus.status) {
		    		case NO_OC:
		    		case OK:  // if the numDupes is == 1, or > 1, that's fine
		        		logger.info(String.format(inner, pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
						break;
		    		case JUMP:  // should NEVER jump ahead, unless we're a non-ex queue and redelivering?  Or during NACK retransmission
		    			oos++;
		    			if (seqStatus.numDupes > 0) {  // have seen this one before
		    				if (prev) {
		    					logger.info(String.format(inner, pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
		    				} else {
		    					logger.warn(String.format(inner, pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
		    				}
		    			} else {  // super bad, never seen before
		    				if (prev) {  // at least we've seen the key before this, so maybe not all bad
		    					logger.info(String.format(inner, pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
		    				} else {
		    					logger.error(String.format(inner + " GAP! BAD!", pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
		    				}
		    			}
						break;
		    		case REWIND:  // rewiding, but this is a redelivered msg, so is ok?
						if (prev) {
							logger.info(String.format(inner, pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
						} else {
							oos++;
							logger.fatal(String.format(inner + " HOW!? PUB NACK?", pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
						}
						break;
		    		default:
		    			throw new AssertionError();
		    		}  // end "is red flag"
		    		
		    	} else {  // not redelivered.  This is usual...
		    		switch (seqStatus.status) {
		    		case NO_OC:
		    		case OK:  // this is the normal
		    			if (seqStatus.numDupes > 0) {  // log it
		            		logger.info(String.format(inner, pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
		    			} else {  // this is the super normal path
		            		if (showEach || !pkSeq.missing.isEmpty())  // will always be empty if trackMissing==false
		            			logger.info(String.format(inner, pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
		            		else logger.debug(String.format(inner, pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
		    			}
						break;
		    		case JUMP:  // should NEVER jump ahead, although less severe if seen before (but no RED flag?)
		    			oos++;
		    			if (seqStatus.numDupes > 0) {
		    				if (prev) {  // at least we've seen the key before this, so maybe not all bad
		    					logger.info(String.format(inner + " " + CharsetUtils.WARNING, pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
		    				} else {
		    					logger.warn(String.format(inner + " GAP!", pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
		    				}
		//        				logger.error(String.format(inner + " but NO red flag!?", pqKey, status.expected, msgSeqNum, plusMinus, prevStr, status.status, delCountStr));
		    			} else {  // super bad, never seen this seq num before
		    				if (prev) {  // at least we've seen the key before this, so maybe not all bad
		    					logger.warn(String.format(inner, pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
		    				} else {
		    					logger.error(String.format(inner + " GAP! BAD!", pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
		    				}
		    			}
						break;
		    		case REWIND:  // rewinding, but has no redelivered flag, so is ok?
						if (prev) {
							logger.info(String.format(inner, pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
						} else {
							oos++;
							logger.fatal(String.format(inner + " HOW!? PUB NACK?", pqKey, seqStatus.expected, msgSeqNum, plusMinus, prevStr, seqStatus.status, dupeCountStr, missingStr));
						}
						break;
		    		default:
		    			throw new AssertionError();
		    		}
		    	}
		    	return seqStatus.numDupes > 0;
		    } else {  // first time seeing this PQ key, or we're not enabled so who cares
		    	if (msgSeqNum == 0 || !isEnabled) {  // ignore
		    		if (showEach) logger.info(String.format(inner, pqKey, 0, msgSeqNum, "---", "-", "NO_OC", "", ""));
		    		else if (logger.isDebugEnabled()) logger.debug(String.format(inner, pqKey, 0, msgSeqNum, "-- ", "-", "NO_OC", "", ""));
		    	} else {
			    	keysToSeqsMap.put(pqKey, new PerKeySequence(pqKey, msgSeqNum));  // new guy!
			    	if (msgSeqNum > 1) {
	//		    		oos++;  // actually, don't count this as an OoSeq, as it causes disturbing stats for viewers
			    		newKs++;
			    		logger.info(String.format(inner, pqKey, 1, msgSeqNum, "+"+msgSeqNum, CharsetUtils.WARNING, "START JUMP", "", ""));
			    	} else if (msgSeqNum == 1) {
			    		newKs++;
			    		if (showEach) logger.info(String.format(inner, pqKey, 1, msgSeqNum, "---", CharsetUtils.PREV_YES, "OK", "", ""));
			    		else if (logger.isDebugEnabled()) logger.debug(String.format(inner, pqKey, 1, msgSeqNum, "-- ", CharsetUtils.PREV_YES, "OK", "", ""));
			    	}
		    	}
		    	return false;
		    }
    	} catch (Exception e) {
    		System.err.println("### Had an issue with an insert..!!!");
    		logger.error("### Had an issue with an insert..!!!");
    		e.printStackTrace();
    		System.err.println(sub + ", " + pqKey + ", " + msgSeqNum + ", " + redeliveredFlag);
    		logger.error(sub + ", " + pqKey + ", " + msgSeqNum + ", " + redeliveredFlag);
    		return false;  // or throw an exception?  I dunno
    	}
	}
}
