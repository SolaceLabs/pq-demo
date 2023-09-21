package dev.solace.pqdemo;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.math3.distribution.PoissonDistribution;
import org.json.JSONObject;

public class Test {

	
	
	static class TestApp extends AbstractParentApp {
		
	}
	
	

	static String toPrettyString(Set<Integer> aSet) {
		List<String> ranges = new ArrayList<>();
		// assuming that aSet is sorted..!  somehow it appears to be?
		int cursor = -1;
		int start = cursor;
		// essentially we're looking for gaps, and ranges, to combine...
		for (int i : aSet) {
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
		if (start == cursor) ranges.add(Integer.toString(start));
		else ranges.add(start + "-" + cursor);
		return ranges.toString();
	}
	
	
	enum Blah {
		TEST,
		;
	}

	public static void main(String... args) {
		
		byte[] test2 = new byte[] { -2, 4, 65, 120 };
		String test22 = new String(test2);
		System.out.println(test22);
		test22 = new String(test2, Charset.forName("ISO-8859-1"));
		System.out.println(test22);
		test22 = new String(test2, Charset.forName("ASCII"));
		System.out.println(test22);
		test22 = new String(test2, Charset.forName("UTF-8"));
		System.out.println(test22);
		System.exit(0);
		
		
		String name = "pq-3";
		String queueNameSimple = name.replaceAll("[^a-zA-Z0-9\\-]", "_");  // replace
		System.out.println(queueNameSimple);
		System.exit(0);
		
		Blah blah3 = null;
		if (blah3 == Blah.TEST) {
			System.out.println("it is!?");
		} else {
			System.out.println("it isn't");
		}
		System.exit(0);
		
		
       final double log2 = Math.log(2);
       for (int msgCount = 1; msgCount <= 100; msgCount++) {
    	   System.out.println(msgCount + ": " + (int)(Math.log(msgCount) / log2));
       }
	   System.exit(0);
		
		
		
		Map<Command,Object> entry = Collections.singletonMap(Command.QUIT, null);
		
		List<List<Integer>> vals = new ArrayList<>();
		for (int i=0; i<10; i++) {
			vals.add(new ArrayList<>());
		}
		for (int i=0; i<100; i++) {
			String s = Integer.toHexString(i);
			vals.get(Math.abs(s.hashCode()) % 10).add(i);
		}
		for (int i=0; i<10; i++) {
			System.out.println(i + ": " + vals.get(i));
		}
		
		
		
		Object o2 = null;
		String a2 = null;
		System.out.println(a2.equalsIgnoreCase((String)o2));
	    System.exit(1);
		
		Integer aa = Integer.valueOf(1);
		Integer bb = Integer.valueOf(1);
		System.out.println(aa.equals(bb));
//		System.out.println(Integer.parseInt("10,000"));  // doesn't work!
//		System.exit(1);
		
		TreeMap<String,AtomicInteger> blah = new TreeMap<>();
		TreeMap<String,Integer> blah2 = new TreeMap<>();
		
		blah.put("test", new AtomicInteger(0));
		blah2.put("test", 0);
		
		while (true) {
//			blah.get("test").incrementAndGet();
			blah2.put("test", blah2.get("test") + 1);
			
			
			if ("1".equals("2")) break;
		}
		
		System.out.println(Math.log(17));
		System.out.println(Math.log(17) / Math.log(16));
		System.out.println(Math.log(255) / Math.log(16));
		System.out.println(Math.log(257) / Math.log(16));
		System.out.println(Math.log(Integer.MAX_VALUE) / Math.log(16));
		System.exit(1);
		
		
		double prob = 0.995;
		Random r = new Random();

		int total = 0;
		int loops = 1000000;
		
		for (int i=0; i<loops; i++) {
			int j = 1;
			while (r.nextDouble() < prob) {
				j++;
			}
			total += j;
		}
		
		System.out.println(total * 1.0 / loops);
		System.exit(0);
		
		
		final int pdMeanMs = 100;
//		final int actualPdValue = (int)Math.sqrt(pdMeanMs);  // so like, 1000
		final double actualPdValue = Math.pow(pdMeanMs, 0.333);  // so like, 1000
//		System.out.println(actualPdValue);
		final double scale = pdMeanMs * 1.0 / actualPdValue;
//		System.out.println(scale);
//		System.out.println();
		
		final int count = 20_000;
		int[] nums = new int[count];
		int max = 0;
		PoissonDistribution pd = new PoissonDistribution(actualPdValue);  // much slower for large numbers, seems inversely proportional to PD size
		PoissonDistribution pd2 = new PoissonDistribution(50);
		long start = System.currentTimeMillis();
		for (int i=0; i<count; i++) {
//			System.out.println(pd.sample());
//			nums[i] = Math.max(0, (int)Math.round(pd.sample() * scale + (Math.random() * scale) - scale/2));
			nums[i] = pd2.sample();
//			int asdf = pd.sample();
//			max = Math.max(max, nums[i]);
			System.out.println(i + "\t" + nums[i]);
//			System.out.println(nums[i]);
		}
		
//		System.out.println("max = " + (max * scale));
//		System.out.println(System.currentTimeMillis() - start);
		System.exit(0);
		
		String payload = "{\"SLOW\":35, \"ACKD\": 1000 }";

		TestApp.stateMap.put(Command.SLOW, 34);
		System.out.println(TestApp.parseStateUpdateMessage(payload));
		
		payload = "{\"SLOW\":35, \"ACKD\": 1200 }";
		System.out.println(TestApp.parseStateUpdateMessage(payload));
		
		System.out.println(-35 % 1000000);
		System.out.println(-999835 % 1000000);
		
		
		JSONObject jo = new JSONObject();
		double val = 0.0;
		jo.put("prob", val);
		System.out.println(jo.toString());
		Object o = jo.get("prob");
		System.out.println(o.getClass().getSimpleName());
		jo = new JSONObject("{\"prob\":0}");
		o = jo.getDouble("prob");
		System.out.println(o.getClass().getSimpleName());
		Double a = Double.valueOf(0);
		Integer b = Integer.valueOf(0);
		System.out.println(a.equals(b));
		double c = 0;
		int d = 0;
		System.out.println(c == d);
		System.out.println(a == d);
		System.out.println(b == d);
//		Double e = (Double)d;
		Set<Object> s = new HashSet<>();
		s.add(1.0);
		System.out.println(s.toArray()[0].getClass().getSimpleName());
		
//		val = Double.valueOf(o);
		
		
		Map<String,Integer> test = new HashMap<>();
		test.put("a", 5);
		test.clear();
		int e = test.get("a");
		System.out.println(test.get("a"));
		

		System.exit(0);
		
		
		
//		Arrays.sort(nums);
//		for (int i=0; i<count; i++) {
//			System.out.println(nums[i]);
//		}

//		System.out.println(max);
		
		
		
		String uuid = UUID.randomUUID().toString();
		System.out.println(uuid);
		System.out.println(uuid.substring(0,4));
		
		System.out.println(UUID.randomUUID().toString().substring(0,4));
		System.out.println(UUID.randomUUID().toString().substring(0,4));
		System.out.println(UUID.randomUUID().toString().substring(0,4));
		System.out.println(UUID.randomUUID().toString().substring(0,4));
		System.out.println(UUID.randomUUID().toString().substring(0,4));
		System.out.println(UUID.randomUUID().toString().substring(0,4));
		System.out.println();
		
		
		
	}
}
