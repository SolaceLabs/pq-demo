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

import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.BytesXMLMessage;

public class BrokerLogFileOnly {

    private static final Logger logger = LogManager.getLogger(BrokerLogFileOnly.class);
    
    public static void log(BytesXMLMessage message) {
    	// from JavaDocs: This method always replaces malformed-input and unmappable-character sequences with this charset's default replacement string
    	String payload = new String(((BytesMessage)message).getData(), StandardCharsets.UTF_8);  // so this won't throw anything
    	if (payload.charAt(payload.length()-1) == 0) payload = payload.substring(0, payload.length()-1);  // NULL-terminated string for some reason!
    	logger.info("BROKER LOG: " + payload);
    }
	
	private BrokerLogFileOnly() {
		throw new AssertionError();
	}
}
