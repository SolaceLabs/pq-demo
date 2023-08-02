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

import java.nio.charset.Charset;

public class CharsetUtils {

	public static final String PREV_YES;
	public static final String PREV_NO;
	public static final String SUB_CHAR;
	public static final String WARNING;
	public static final String RED_FLAG;
	public static final String WAVE;
	
	static {
		// need to deal with weird chars on Windows Command Prompt due to lack of UTF-8 support
		if (Charset.defaultCharset().equals(Charset.forName("UTF-8"))) {
			PREV_YES = "✔";
			PREV_NO = "❌";
			SUB_CHAR = "●";
			WARNING = "⚠";
			RED_FLAG = " reD🚩 ";
			WAVE = " 👋 🏼";
		} else {
			PREV_YES = "Y";
			PREV_NO = "X";
			SUB_CHAR = "*";
			WARNING = "!";
			RED_FLAG = " reD";
			WAVE = "";
		}
	}
	private CharsetUtils() {
		throw new AssertionError("Utility class, don't instantiate!");
	}
}
