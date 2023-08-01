package dev.solace.pqdemo;

import java.nio.charset.Charset;

public class CharsetUtils {

	public static final String PREV_YES;
	public static final String PREV_NO;
	public static final String SUB_CHAR;
	public static final String WARNING;
	public static final String RED_FLAG;
	
	
	
	static {
		// need to deal with weird chars on Windows Command Prompt due to lack of UTF-8 support
		if (Charset.defaultCharset().equals(Charset.forName("UTF-8"))) {
			PREV_YES = "✔";
			PREV_NO = "❌";
			SUB_CHAR = "●";
			WARNING = "⚠";
			RED_FLAG = "RED🚩";
		} else {
			PREV_YES = "Y";
			PREV_NO = "X";
			SUB_CHAR = "*";
			WARNING = "!";
			RED_FLAG = "RED";
		}
	}
	
	
	
}
