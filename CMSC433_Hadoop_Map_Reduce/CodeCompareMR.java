package cmsc433.p3;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Scanner;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;

//import com.sun.xml.internal.bind.v2.model.core.TypeInfo;


/**
 * This class takes a source code file and constructs a hash of the tokenized
 * code. It then uses Hadoop to scan code in posts for matching tokens to deduce
 * a relevance score for each post, and outputs a list of each post's title
 * along with this score.
 */
public class CodeCompareMR {

	/** The minimum size for a string to form a valid token */
	protected static final int MIN_TOKEN_SIZE = 3;

	/** Dictionary representation of source file, mapping tokenized strings
	 * to counts of how often they appear */
	protected static final ConcurrentHashMap<String, Integer> source = new ConcurrentHashMap<String, Integer>();


	public static class TokenizerMapper extends
	Mapper<Object, Text, Text, Text> {

		/** Regex string used to extract code from the post body */
		private static final String regex = "<code>(.+?)</code>";
		/** Regex used to extract code compiled as automata */
		private static final Pattern pattern = Pattern.compile(regex);
		//private final static IntWritable one = new IntWritable(1);

		private String title;  // title of post
		private String body;  // body of post

		@Override
		public void map(Object key, Text value, Context context)
		throws IOException, InterruptedException {
			String post = value.toString();
			// The following code is used to parse the CSV efficiently.
			// It's not really important how it works since we did it for you.
			int count = 0;  // which field we're on
			int startIndex = 0;  // start index for current field
			boolean escape = true;  // whether we're outside a reading region
			// Iterate over the CSV by character
			for (int index = 0; index < post.length(); index++) {
				if (post.charAt(index) == '"') {
					if (index+1 >= post.length() || post.charAt(index+1) == ',') {  // ending quotation
						if (count == 7)
							body = post.substring(startIndex, index);  // extract body
						else if (count == 14)
							title = post.substring(startIndex, index);  // extract title
						index++;
						count++;
						escape = true;
					} else if (!escape && post.charAt(index+1) == '"')  // escaped quotes within a field
						index++;
					else {  // beginning quotation
						startIndex = index+1;
						escape = false;
					}
				}
			}
			if (body != null) {  // just in case data is malformed
				Matcher matcher = pattern.matcher(body);
				// Iterate over code blocks
				while (matcher.find()) {
					String match = matcher.group(1);
					// Break into tokens
					StringTokenizer wordItr = new StringTokenizer(match);
					while (wordItr.hasMoreTokens()) {
						String next = wordItr.nextToken();
						if (next.length() >= MIN_TOKEN_SIZE) {  // must be of valid length
							if(title != null && next != null){
								Text tit = new Text();
								tit.set(title);
								Text tet = new Text();
								tet.set(next);
								context.write(tit, tet);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * This class takes the (title, token) pairs from the mapper and outputs
	 * (title, relevance) pairs. For every X copies of a certain token in the
	 * source, if a post contains Y copies of the same token, then the relevance
	 * count increased by XY.
	 */
	public static class HashReducer extends
	Reducer<Text, Text, Text, IntWritable> {
		private IntWritable result = new IntWritable();

		@Override
		public void reduce(Text key, Iterable<Text> values,
				Context context) throws IOException, InterruptedException {
			//source: hash from token to count
			//values: hash from url to token
			int accumulator = 0;
			//System.out.println("key: " + key);
			for(Text t: values){
				//System.out.println(t.toString());
				for(String st: source.keySet()){
					if(t.toString().equals(st)){
						accumulator += source.get(st);
					}
				}

			}
			//System.out.println("acc: " + accumulator);
			result.set(accumulator);
			context.write(key, result);
		}
	}


	/**
	 * Creates a dictionary of counts as a hash map, mapping valid tokens from
	 * the source to the number of times they appear.
	 * @param input Source file to read from
	 */
	public static void tokenizeSource(File input) {
		try {
			source.clear();
			Scanner sc = new Scanner(input);
			while (sc.hasNext()) {
				String next = sc.next();
				if (next.length() >= MIN_TOKEN_SIZE) {  // must be of valid length
					Integer val;
					// This isn't thread-safe, but since we're building in serial it's OK
					if ((val = source.putIfAbsent(next, 1)) != null)
						source.put(next, val+1);
				}
			}

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}


	/**
	 * This method performs token-matching on the source and input by
	 * configuring the job as appropriate and using Hadoop. It then identifies
	 * posts by their relevancy and outputs them as a list.
	 * @param job Job created for this function
	 * @param source String representing source code file to perform lookup on
	 * @param input String representing location of input directory
	 * @param output String representing location of output directory
	 * @return True if successful, false otherwise
	 * @throws Exception
	 */
	public static boolean evaluate(Job job, String source, String input, String output) throws Exception {
		// tokenize source file
		tokenizeSource(new File(source));
		job.setJarByClass(CodeCompareMR.class);
		job.setMapperClass(TokenizerMapper.class);
		job.setReducerClass(HashReducer.class);

		// Describe the input- and output-specifications
		// for the Map-Reduce job.
		job.setInputFormatClass(TextInputFormat.class);
		job.setOutputFormatClass(TextOutputFormat.class);

		// Set the types of the output keys and values
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(Text.class);

		// Indicate where to find input
		FileInputFormat.addInputPath(job, new Path(input));

		// Indicate where to write output
		FileOutputFormat.setOutputPath(job, new Path(output));

		// Submit the job to the cluster and wait for it to finish.
		return job.waitForCompletion(true);
	}
}
