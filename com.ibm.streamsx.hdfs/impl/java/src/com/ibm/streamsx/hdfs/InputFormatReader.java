/* Copyright (C) 2015, International Business Machines Corporation */
/* All Rights Reserved */

package com.ibm.streamsx.hdfs;


import java.io.IOException;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.log4j.Logger;

import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.Attribute;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingData.Punctuation;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.Type.MetaType;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.OutputPortSet.WindowPunctuationOutputMode;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import java.io.File;

/**
 * Operator to read input formats.
 */
@Libraries({"opt/downloaded/*","opt/inputformatreader/*"})
@PrimitiveOperator(name="InputFormatReader", namespace="com.ibm.streamsx.hdfs",
description="Java Operator InputFormatReader")
@OutputPorts({@OutputPortSet(description="Port that produces tuples", cardinality=1, optional=false, windowPunctuationOutputMode=WindowPunctuationOutputMode.Generating), @OutputPortSet(description="Optional output ports", optional=true, windowPunctuationOutputMode=WindowPunctuationOutputMode.Generating)})
public class InputFormatReader extends AbstractOperator {

	private static final String INPUT_PARAM_NAME = "file";
	private static final String FILETYPE_PARAM_NAME="fileType";
	Logger logger = Logger.getLogger(this.getClass());
	
	public static enum FileType {
		text,
		sequence
	}
	
	private FileType fileType = FileType.text;
	
	@Parameter(name=FILETYPE_PARAM_NAME,description="Type of file.  Use text for uncompressed and uncompressed text files and sequence for sequence files",optional=true)
	public void setFileType(FileType type) {
		fileType = type;
	}
	
	
	
	
	private static final String CONFIG_RESOURCES_PARAM_NAME = "configResources";


	List<InputSplit> splits;
	/**
	 * Thread for calling <code>produceTuples()</code> to produce tuples 
	 */
    private Thread processThread;
    
    

    private String inputFiles[];
    private String configResources[];
    FileInputFormat<LongWritable,Text> inputFormat;
    int keyIndex = -1;
    int valueIndex = -1;
    
    int channel = -1;
    int maxChannels = -1;
    Configuration conf;
    
    
    @Parameter(name=INPUT_PARAM_NAME,description="Paths to be used as input")
    public void setFile(String[] files ) {
    	inputFiles = files;
    }
    
    @Parameter(name=CONFIG_RESOURCES_PARAM_NAME,optional=true,description="Resources to be added to the configuration")
    public void setResources(String[] paths) {
    	configResources = paths;
    }
    
    @ContextCheck(compile=true)
    public static void compileChecks(OperatorContextChecker checker) {	
    	StreamSchema outSchema= checker.getOperatorContext().getStreamingOutputs().get(0).getStreamSchema();
    	
    	Attribute keyAttr = outSchema.getAttribute("key");
    	Attribute valueAttr = outSchema.getAttribute("value");
    	
    	
    	if (keyAttr == null && valueAttr == null) {
    		checker.setInvalidContext("Either key or value must be on output stream ", new Object[0]);
    	}
    	
    	if (checker.getOperatorContext().getNumberOfStreamingOutputs() != 1) {
    		checker.setInvalidContext("Number of streaming outputs must be 1",new Object[0]);
    	}
    }
    
    // TODO features
    
    
    @ContextCheck(compile=false)
    public static void runtimeCheck(OperatorContextChecker checker) { 
    	StreamSchema outSchema= checker.getOperatorContext().getStreamingOutputs().get(0).getStreamSchema();
    	
    	Attribute keyAttr = outSchema.getAttribute("key");
    	Attribute valueAttr = outSchema.getAttribute("value");

    	
    	if (keyAttr != null  && keyAttr.getType().getMetaType() != MetaType.INT64) {
    		checker.setInvalidContext("Type of key on output stream must be int64", new Object[0]);
    	}
    	
    	if (valueAttr != null && valueAttr.getType().getMetaType() != MetaType.RSTRING 
    			&& valueAttr.getType().getMetaType() != MetaType.USTRING) {
    		checker.setInvalidContext("Type of value on output stream must be RString or ustring", new Object[0]);
    	}
    	
    }
    
    /**
     * Initialize this operator. Called once before any tuples are processed.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void initialize(OperatorContext context)
            throws Exception {
    	// Must call super.initialize(context) to correctly setup an operator.
        super.initialize(context);
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
        

        StreamingOutput<OutputTuple> out = context.getStreamingOutputs().get(0);
        StreamSchema outSchema = out.getStreamSchema();
        
        channel = context.getChannel();
        maxChannels = context.getMaxChannels();
        
        // This will make the for loop in process tuples work right--we take splits 
        // channel + k* maxChannels.
        if (channel < 0) {
        	channel = 0;
        	maxChannels = 1;
        }
        
        if (outSchema.getAttribute("key") != null) {
        	keyIndex = outSchema.getAttributeIndex("key");
        }
        if (outSchema.getAttribute("value") != null) {
        	valueIndex = outSchema.getAttributeIndex("value");
        }
        
        // Establish input splits.
		try {
			
		//Class<FileInputFormat<K, V>> inputFormatClass= (Class<FileInputFormat<K, V>>) Class.forName(inputFormatClassname);	
		//FileInputFormat<K,V> inputFormat = inputFormatClass.newInstance();
	
		if (fileType == FileType.text) {
			inputFormat = new TextInputFormat();
		}
		else {
			inputFormat = new SequenceFileInputFormat();
		}

		conf = new Configuration();
		if (configResources != null ) {
		for (String s : configResources) {
            File toAdd = new File(s);
            String pathToFile;
            if (toAdd.isAbsolute()) {
                pathToFile = toAdd.getAbsolutePath();
            }
            else {
                pathToFile = context.getPE().getApplicationDirectory()+File.separator+s;
                toAdd = new File(pathToFile);
		    }
            if (!toAdd.exists()) {
                throw new Exception("Specified configuration file "+s+" not found at "+pathToFile);
            }
           logger.info("Adding "+pathToFile+" as config resource");
            conf.addResource(new Path(pathToFile));
		}
		String defaultFS = conf.get("fs.defaultFS");
		if (!defaultFS.startsWith("hdfs")) {
			logger.warn("Default file system not HDFS; may be configuration problem");
		}
		logger.debug("Default file system is "+defaultFS);
        }
		Job job = Job.getInstance(conf);
		
		for (String p : inputFiles) {
			FileInputFormat.addInputPath(job, new Path(p));
		}
		
		splits = inputFormat.getSplits(job);
        logger.info("There are "+splits.size()+" splits");
		}
		catch (IOException e ) {
			throw e;
		}
        
        
        processThread = getOperatorContext().getThreadFactory().newThread(
                new Runnable() {

                    @Override
                    public void run() {
                        try {
                            produceTuples();
                        } catch (Exception e) {
                            Logger.getLogger(this.getClass()).error("Operator error", e);
                        }                    
                    }
                    
                });
        
        /*
         * Set the thread not to be a daemon to ensure that the SPL runtime
         * will wait for the thread to complete before determining the
         * operator is complete.
         */
        processThread.setDaemon(false);
    }

    /**
     * Notification that initialization is complete and all input and output ports 
     * are connected and ready to receive and submit tuples.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void allPortsReady() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " all ports are ready in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
    	// Start a thread for producing tuples because operator 
    	// implementations must not block and must return control to the caller.
        processThread.start();
    }
    
    /**
     * Submit new tuples to the output stream
     * @throws Exception if an error occurs while submitting a tuple
     */
    private void produceTuples() throws Exception  {
        final StreamingOutput<OutputTuple> out = getOutput(0);

        for (int i = channel; i < splits.size(); i = i + maxChannels) {
        	if (logger.isInfoEnabled()) {
        		logger.info("Handling split "+i);
        	}
        	TaskAttemptContext context = new TaskAttemptContextImpl(conf, new TaskAttemptID("channel "+channel+" of "+maxChannels,0,TaskType.MAP,i,0));

			RecordReader<LongWritable,Text> reader = inputFormat.createRecordReader(splits.get(i),context);
			reader.initialize(splits.get(i), context);
			
			while (reader.nextKeyValue()) {
				OutputTuple toSend = out.newTuple();
				// TODO set filename, if it makes sense.
				if (keyIndex >= 0) {
					toSend.setLong(keyIndex, reader.getCurrentKey().get());
				}
				if (valueIndex >= 0) {
					toSend.setString(valueIndex, reader.getCurrentValue().toString());
				}
				out.submit(toSend);
			}
			out.punctuate(Punctuation.WINDOW_MARKER);
        }
        out.punctuate(Punctuation.FINAL_MARKER);
    }

    /**
     * Shutdown this operator, which will interrupt the thread
     * executing the <code>produceTuples()</code> method.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    public synchronized void shutdown() throws Exception {
        if (processThread != null) {
            processThread.interrupt();
            processThread = null;
        }
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
        
        // TODO: If needed, close connections or release resources related to any external system or data store.

        // Must call super.shutdown()
        super.shutdown();
    }
}
