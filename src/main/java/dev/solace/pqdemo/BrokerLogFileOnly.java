package dev.solace.pqdemo;

import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.solacesystems.jcsmp.BytesMessage;
import com.solacesystems.jcsmp.BytesXMLMessage;

public class BrokerLogFileOnly {

    private static final Logger logger = LogManager.getLogger(BrokerLogFileOnly.class);
    
    public static void log(BytesXMLMessage message) {
    	String payload = new String(((BytesMessage)message).getData(), StandardCharsets.UTF_8);
    	if (payload.charAt(payload.length()-1) == 0) payload = payload.substring(0, payload.length()-1);
    	logger.info("BROKER LOG: " + payload);
    }
	
	private BrokerLogFileOnly() {
		throw new AssertionError();
	}
}
