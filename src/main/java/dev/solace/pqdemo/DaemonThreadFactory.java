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

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public enum DaemonThreadFactory implements ThreadFactory {

	INSTANCE;
	
	String name = "default";

	public static class ExceptionHandler implements UncaughtExceptionHandler {
		@Override
		public void uncaughtException(Thread t, Throwable e) {
			System.err.println("thread " + t + " had an exception!");
			e.printStackTrace();
			Runtime.getRuntime().halt(1);
		}
	}
	
	public ThreadFactory withName(String name) {
		this.name = name;
		return this;
	}
	
	
//	private static AtomicInteger threadCount = new AtomicInteger(0);
	
	@Override
	public Thread newThread(Runnable r) {
		Thread t = Executors.defaultThreadFactory().newThread(r);
//		t.setName("aaron_" + threadCount.incrementAndGet());
		t.setName(name);
		t.setDaemon(true);
		return t;
	}
	
	private DaemonThreadFactory() {
		
	}
	

}
