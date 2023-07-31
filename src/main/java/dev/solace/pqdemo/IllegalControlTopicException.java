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

public class IllegalControlTopicException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	public IllegalControlTopicException(String illegalTopic) {
		super(buildMessage(illegalTopic));
	}

	public IllegalControlTopicException(String illegalTopic, Throwable cause) {
		super(buildMessage(illegalTopic), cause);
	}

	public IllegalControlTopicException(Command command, String illegalTopic) {
		super(buildMessage(command, illegalTopic));
	}

	public IllegalControlTopicException(Command command, String illegalTopic, Throwable cause) {
		super(buildMessage(command, illegalTopic), cause);
	}

	private static String buildMessage(String illegalTopic) {
    	return "Illegal Control topic! '" + illegalTopic + "'";
	}
	
	private static String buildMessage(Command cmd, String illegalTopic) {
    	return "Illegal Control topic for Command " + cmd.name() + "! '" + illegalTopic + "'. Expecting: " + cmd.datatype;
	}

}
