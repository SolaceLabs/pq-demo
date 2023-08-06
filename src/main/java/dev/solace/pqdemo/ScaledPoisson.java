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
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.commons.math3.distribution.PoissonDistribution;

public class ScaledPoisson {
	
	private static final double POW_SCALE = 0.25;
	private static final Random r = new Random();
	
	private final PoissonDistribution pd;
	private final int originalMean;
	private final int adjustedMean;
	private final int adjust;
	private final double scaledPdMean;
	private final double scale;
	
	
	public ScaledPoisson(int mean) {
		this.originalMean = mean;
//		if (mean > 1) {
//			this.adjustedMean = mean - 1;
//			this.adjust = 1;
//		} else {
			this.adjustedMean = mean;
			this.adjust = 0;
//		}
		this.scaledPdMean = Math.pow(adjustedMean, POW_SCALE);
		this.scale = adjustedMean / scaledPdMean;
		this.pd = new PoissonDistribution(scaledPdMean);
//		System.out.printf("originalMean=%d, scaledMean=%.2f, scale=%.2f%n", originalMean, scaledPdMean, scale);
	}
	
	public int sample() {
		return Math.max(adjust, (int)Math.round((pd.sample() + r.nextDouble()) * scale - scale/2) + adjust);
	}
	
	public int getMean() {
		return originalMean;
	}
	
	public double getScaledPdMean() {
		return scaledPdMean;
	}
	
	public double getScale() {
		return scale;
	}
	
	
	
	

	public static void main(String... args) {

		final int pdMeanMs = 100;

//		System.out.println(new ScaledPoisson(pdMeanMs).getScaledPdMean());
//		System.exit(0);
//		final double powValue = 0.25;
//		final double actualPdValue = Math.pow(pdMeanMs, powValue);
//		final double scale = pdMeanMs / actualPdValue;
//		final double powValue2 = 0.5;
//		final double actualPdValue2 = Math.pow(pdMeanMs, powValue2);
//		final double scale2 = pdMeanMs / actualPdValue2;
		
		final int count = 10_000;
		PoissonDistribution pd1 = new PoissonDistribution(1);  // much slower for large numbers, seems inversely proportional to PD size
		PoissonDistribution pd2 = new PoissonDistribution(pdMeanMs);  // much slower for large numbers, seems inversely proportional to PD size
//		PoissonDistribution pd3 = new PoissonDistribution(actualPdValue);  // much slower for large numbers, seems inversely proportional to PD size
//		PoissonDistribution pd4 = new PoissonDistribution(actualPdValue2);  // much slower for large numbers, seems inversely proportional to PD size
		ScaledPoisson scaledPd = new ScaledPoisson(pdMeanMs);
		
//		PoissonDistribution pd5 = new PoissonDistribution(10);
//		PoissonDistribution pd6 = new PoissonDistribution(10,0);
//		PoissonDistribution pd7 = new PoissonDistribution(10,1);
//		PoissonDistribution pd8 = new PoissonDistribution(10,1555.5);

		// timing test
//		long start = System.currentTimeMillis();
//		for (int i=0; i<count; i++) {
//			scaledPd.sample();
//		}
//		System.out.println(System.currentTimeMillis() - start);
//		System.exit(0);
		
		List<Integer> l1 = new ArrayList<>();
		List<Integer> l2 = new ArrayList<>();
		List<Integer> l3 = new ArrayList<>();
//		List<Integer> l4 = new ArrayList<>();
		
		int[] b1 = new int[pdMeanMs * 3];
		Arrays.fill(b1, 0);
		int[] b2 = new int[pdMeanMs * 3];
		Arrays.fill(b2, 0);
		int[] b3 = new int[pdMeanMs * 3];
		Arrays.fill(b3, 0);
		int[] b4 = new int[pdMeanMs * 3];
		Arrays.fill(b4, 0);
		
		
		for (int i=0; i<count; i++) {
			l1.add(pd1.sample() * pdMeanMs);
			l2.add(pd2.sample());
			l3.add(scaledPd.sample());
//			l4.add(Math.max(0, (int)Math.round(pd4.sample() * scale2 + (Math.random() * scale2) - scale2/2)));
			
//			l1.add(pd5.sample());
//			l2.add(pd6.sample());
//			l3.add(pd7.sample());
//			l4.add(pd8.sample());
		}
		l1.sort(null);
		l2.sort(null);
		l3.sort(null);
//		l4.sort(null);

		System.out.println("v="+pdMeanMs + "\t"+pdMeanMs+"*PD_1\tPD_"+pdMeanMs + "\tsPD_p"+POW_SCALE);
//		System.out.println("v="+pdMeanMs + "\t"+pdMeanMs+"*PD_1\tPD_"+pdMeanMs + "\tsPD_p"+POW_SCALE + "\tPD_p"+powValue2);
		for (int i=0; i<l1.size(); i++) {
			System.out.println(i + "\t" + l1.get(i) + "\t" + l2.get(i) + "\t" + l3.get(i));
//			System.out.println(i + "\t" + l1.get(i) + "\t" + l2.get(i) + "\t" + l3.get(i) + "\t" + l4.get(i));
		}
		
		for (int i=0; i<count; i++) {
			b1[Math.min(b1.length-1, l1.get(i))]++;
			b2[Math.min(b2.length-1, l2.get(i))]++;
			b3[Math.min(b3.length-1, l3.get(i))]++;
//			b4[Math.min(b4.length-1, l4.get(i))]++;
		}
		
//		System.out.println("v="+pdMeanMs + "\tPD_"+pdMeanMs + "\tPD_p"+POW_SCALE + "\tPD_p"+powValue2);
//		for (int i=0; i<b1.length; i++) {
//			System.out.println(i + "\t" + /* b1[i] + "\t" + */ b2[i] + "\t" + b3[i] + "\t" + b4[i]);
//		}
		
	}
}
