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

public enum Command {
	// both
	QUIT(0, "Both", "Gracefully quit all running apps", "pq-demo/control-all/quit"),
	KILL(0, "Both", "Forcefully kill all running apps", "pq-demo/control-all/kill"),
	STATE(0, "Both", "Have all running apps dump their state configuration", "pq-demo/control-all/state"),
	DISP(1, "Both", "Choose Console display type: each message, or aggregate", "pq-demo/control-all/disp/agg", "string [each|agg]", String.class, "each", -1, -1),
	
	// publisher
	PAUSE(0, "Pub", "Pause or unpause publishing temporarily", "pq-demo/control-all/pause"),
	RATE(1, "Pub", "Publish messaage rate", "pq-demo/control-all/rate/300", "integer [0..10000]", Integer.class, 2, 0, 10_000),
	KEYS(1, "Pub", "Number of keys available (per-publisher)", "pq-demo/control-all/keys/1000, pq-demo/control-all/keys/max", "'max', or integer [1..2147483647]", Integer.class, 8, 1, Integer.MAX_VALUE),
	PROB(1, "Pub", "Probability of \"follow-on\" message (same key, next seqNum)", "pq-demo/control-all/prob/0.25", "decimal [0..1]", Double.class, 0.0, 0, 1),
	DELAY(1, "Pub", "Mean time in ms (scaled Poisson dist) to delay follow-on message", "pq-demo/control-all/delay/2000", "integer [0..30000]", Integer.class, 0, 0, 30_000),
	SIZE(1, "Pub", "Size in bytes of published messages", "pq-demo/control-all/size/1024", "integer [0..100000]", Integer.class, 0, 0, 100_000),
	
	// subscribers
	SLOW(1, "Sub", "Slow consumer, mean time in ms (scaled Poisson dist) to take to \"process\" a message", "pq-demo/control-all/slow/50", "integer [0..1000]", Integer.class, 0,0, 1000),
	ACKD(1, "Sub", "Exact ACK Delay / Flush time in ms, for simulating batched processing", "pq-demo/control-all/flush/1000", "integer [0..30000]", Integer.class, 0, 0, 30_000),
	;
		
	final int numParams;
	final String who;
	final String description;
	final String datatype;
	final Class<?> objectType;
	final String example;
	final Object defaultVal;
	final int min;
	final int max;
	
	Command(int numParams, String who, String description, String example) {
		this.numParams = numParams;
		this.who = who;
		this.description = description;
		this.datatype = null;
		this.objectType = null;
		this.example = example;
		this.defaultVal = null;
		min = -1;
		max = -1;
	}

	Command(int numParams, String who, String description, String example, String datatype, Class<?> objectType, Object defaultVal, int min, int max) {
		this.numParams = numParams;
		this.who = who;
		this.description = description;
		this.example = example;
		this.datatype = datatype;
		this.objectType = objectType;
		this.defaultVal = defaultVal;
		this.min = min;
		this.max = max;
	}

	public String toFancyString() {
		return String.format("  %s:%" + (6-name().length()) + "s%s%n         %s,  %d param%s,%s%s  e.g. '%s'",
				name(), " ", description,
				who,
				numParams,
				(numParams==1 ? "" : "s"),
				datatype == null ? "" : " " + datatype,
				defaultVal == null ? "" : ", default=" + defaultVal + ",",
				example);
	}
	
	public static void printHelp() {
		System.out.println("Shows all Commands that the PQ Demo responds to (using topics)");
		System.out.println();
		for (Command cmd : Command.values()) {
			System.out.println(cmd.toFancyString());
		}
		System.out.println();
		System.out.println("Use REST Messaging! E.g. curl -X POST http://localhost:9000/pq-demo/control-all/rate/100");
		System.out.println("Use per-client topic with \"-name\" for individual control: 'pq-demo/control-8312/slow/10'");
		System.out.println("Also, can force state with JSON payload:");
		System.out.println("  curl http://localhost:9000/pq-demo/state/force -d '{\"PROB\":0.5,\"DISP\":\"agg\",\"RATE\":100,\"KEYS\":256}'");
		System.out.println();
	}
	
	public static void main(String... args) {
		printHelp();
	}

}
