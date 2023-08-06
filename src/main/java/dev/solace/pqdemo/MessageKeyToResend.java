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

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.SDTException;
import com.solacesystems.jcsmp.XMLMessage.MessageUserPropertyConstants;

public class MessageKeyToResend implements Comparable<MessageKeyToResend> {

	final String pqKey;
	final int seqNo;
	final long timeToSendNs;
	final boolean wasNack;
	
	MessageKeyToResend(String pqKey, int seqNo, long timeToSendMs) {
		this.pqKey = pqKey;
		this.seqNo = seqNo;
		this.timeToSendNs = timeToSendMs;
		this.wasNack = false;
	}
	
	/** Used when we have a NACK, requeue this message to resend again, keep all the same details.
	 * Note that we can't use msg.reset() in the main loop b/c it could blank all these details before we copy them.
	 * Need to create a new message each loop.
    */
	MessageKeyToResend(BytesXMLMessage origMsg) {
		String key = "N/A";
		try {
			key = origMsg.getProperties().getString(MessageUserPropertyConstants.QUEUE_PARTITION_KEY);
			// key == Xb-8a3b
//			pqKeyInt = Integer.parseInt(key.substring(key.indexOf('-')+1), 16);  // parse out the key int
			pqKey = key;
			seqNo = origMsg.getSequenceNumber().intValue();
//			timeToSendNs = origMsg.getSenderTimestamp();
			timeToSendNs = origMsg.getProperties().getLong("origNsTs");
			wasNack = true;
		} catch (Exception e) {  // should never happen, we know/set what is in the SDT Map
			System.err.println("Something went horribly wrong, this resent message must have a partition key!");
			System.err.println(origMsg.dump());
			System.err.println(this.toString());
			e.printStackTrace();
			System.err.println(e);
			System.err.println(key);
			Runtime.getRuntime().halt(1);
			throw new AssertionError("DEAD");
		}
	}
	
	public boolean sameAs(BytesXMLMessage msg) {
		try {
			String key = msg.getProperties().getString(MessageUserPropertyConstants.QUEUE_PARTITION_KEY);
//			int msgPqKeyInt = Integer.parseInt(key.substring(key.indexOf('-')+1), 16);  // parse out the key int
			int msgSeqNo = msg.getSequenceNumber().intValue();
			long msgTimeToSendNs = msg.getProperties().getLong("origNsTs");
			return this.pqKey.equals(key) && this.seqNo == msgSeqNo && this.timeToSendNs == msgTimeToSendNs;
		} catch (SDTException e) {
			return false;
		}
	}

	@Override
	public String toString() {
//		return String.format("[%s,%d]", Integer.toHexString(pqKeyInt), seqNo);
		return String.format("[%s,%d]", pqKey, seqNo);
	}
	
	@Override
	public boolean equals(Object o) {
        if (o == null || !(o instanceof MessageKeyToResend))
            return false;
        MessageKeyToResend msg = (MessageKeyToResend)o;
        return pqKey.equals(msg.pqKey) && seqNo == msg.seqNo && timeToSendNs == msg.timeToSendNs;
//        return pqKeyInt == msg.pqKeyInt && seqNo == msg.seqNo && timeToSendNs == msg.timeToSendNs;
	}
	
	@Override
	public int hashCode() {
		return pqKey.hashCode() ^ seqNo ^ (int)(timeToSendNs & 0x0000ffff);
//		return pqKeyInt ^ seqNo ^ (int)(timeToSendNs & 0x0000ffff);
	}

	/** Sort by timestamp */
	@Override
	public int compareTo(MessageKeyToResend msg) {
		return Long.compare(this.timeToSendNs, msg.timeToSendNs);
	}
}
