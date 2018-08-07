package mrte.util;

import java.io.OutputStream;
import java.io.PrintWriter;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;

public class CommandLineOption{
	final static String HELP_MSG_HEADER = "--- Command Usage ------------------------------------------------------" ;
	final static String HELP_MSG_FOOTER = "------------------------------------------------------------------------" ;
	
	final CommandLine cmdLine;
	final Options options;
	final CommandLineParser parser;
	
	public CommandLineOption(String[] args) throws Exception{
		try{
			this.options = generateCmdLineOptions();
			this.parser = new PosixParser();
			// this.parser = new GnuParser();
			this.cmdLine = parser.parse(options, args);
		}catch(Exception ex){
			throw ex;
		}
	}
	
	public boolean getBooleanParameter(String paramName, boolean defaultValue) throws IllegalArgumentException, NumberFormatException{
		if(!cmdLine.hasOption(paramName)){
			return defaultValue;
		}else{
			String temp = cmdLine.getOptionValue(paramName);
			if(temp==null || temp.trim().length()<=0){
				return defaultValue;
			}
			
			boolean boolValue = (temp.equalsIgnoreCase("yes") || temp.equalsIgnoreCase("true") || temp.equalsIgnoreCase("y") || temp.equalsIgnoreCase("on"));
			return boolValue;
		}
	}
	
	public long getLongParameter(String paramName) throws IllegalArgumentException, NumberFormatException{
		if(!cmdLine.hasOption(paramName)){
			throw new IllegalArgumentException("Option incorrect : parameter '"+paramName+"' is required");
		}else{
			String temp = cmdLine.getOptionValue(paramName);
			if(temp==null || temp.trim().length()<=0){
				throw new IllegalArgumentException("Option incorrect : parameter '"+paramName+"' is required");
			}
			long longValue = Long.parseLong(temp);
			return longValue;
		}
	}
	
	public long getLongParameter(String paramName, long defaultValue) throws IllegalArgumentException, NumberFormatException{
		if(!cmdLine.hasOption(paramName)){
			return defaultValue;
		}else{
			String temp = cmdLine.getOptionValue(paramName);
			if(temp==null || temp.trim().length()<=0){
				return defaultValue;
			}
			long longValue = Long.parseLong(temp);
			return longValue;
		}
	}
	
	public int getIntParameter(String paramName) throws IllegalArgumentException, NumberFormatException{
		if(!cmdLine.hasOption(paramName)){
			throw new IllegalArgumentException("Option incorrect : parameter '"+paramName+"' is required");
		}else{
			String temp = cmdLine.getOptionValue(paramName);
			if(temp==null || temp.trim().length()<=0){
				throw new IllegalArgumentException("Option incorrect : parameter '"+paramName+"' is required");
			}
			int intValue = Integer.parseInt(temp);
			return intValue;
		}
	}
	
	public int getIntParameter(String paramName, int defaultValue) throws IllegalArgumentException, NumberFormatException{
		if(!cmdLine.hasOption(paramName)){
			return defaultValue;
		}else{
			String temp = cmdLine.getOptionValue(paramName);
			if(temp==null || temp.trim().length()<=0){
				return defaultValue;
			}
			
			int intValue = Integer.parseInt(temp);
			return intValue;
		}
	}
	
	public double getDoubleParameter(String paramName, double defaultValue) throws IllegalArgumentException, NumberFormatException{
		if(!cmdLine.hasOption(paramName)){
			return defaultValue;
//			System.out.println("Option incorrect : parameter '"+paramName+"' is required");
//			HelpFormatter f = new HelpFormatter();
//			f.printHelp("Usage::", options);
//			throw new IllegalArgumentException("Option incorrect : parameter '"+paramName+"' is required");
		}else{
			String temp = cmdLine.getOptionValue(paramName);
			if(temp==null || temp.trim().length()<=0){
				return defaultValue;
				// throw new IllegalArgumentException("Option incorrect : parameter '"+paramName+"' is required");
			}
			
			try{
				double zipfianValue = Double.parseDouble(temp);
				return zipfianValue;
			}catch(Throwable t){
				return defaultValue;
			}
		}
	}
	
	public String getStringParameter(String paramName) throws IllegalArgumentException, NumberFormatException{
		if(!cmdLine.hasOption(paramName)){
			throw new IllegalArgumentException("Option incorrect : parameter '"+paramName+"' is required");
		}else{
			String value = cmdLine.getOptionValue(paramName);
			if(value==null || value.trim().length()<=0){
				throw new IllegalArgumentException("Option incorrect : parameter '"+paramName+"' is required");
			}
			return value;
		}
	}
	
	public String getStringParameter(String paramName, String defaultValue) throws IllegalArgumentException, NumberFormatException{
		if(!cmdLine.hasOption(paramName)){
			return defaultValue;
		}else{
			String value = cmdLine.getOptionValue(paramName);
			if(value==null || value.trim().length()<=0){
				return defaultValue;
			}
			return value;
		}
	}
	
	protected Options generateCmdLineOptions(){
		Options options = new Options();
		options.addOption("mu", "mysql_url", true, "MySQL target database jdbc url");
		options.addOption("mi", "mysql_init_connections", true, "MySQL initial connections to prepare before replay collected statement");
		options.addOption("md", "mysql_default_db", true, "MySQL default database");
		options.addOption("mf", "mysql_force_default_db", true, "Force MySQL default database");
		
		options.addOption("rm", "database_remap", true, "Database remapping");
		options.addOption("mp", "max_packet_size", true, "Maximum mysql packet size");
		
		options.addOption("nu", "mongo_url", true, "MongoDB(queue) connection url");
		options.addOption("nd", "mongo_db", true, "MongoDB(queue) database name");
		options.addOption("nc", "mongo_collectionprefix", true, "MongoDB(queue) collection name prefix");
		options.addOption("ns", "mongo_collections", true, "MongoDB(queue) collections partitioned");
		
		options.addOption("sq", "slow_query_time", true, "MySQL slow query time in milli seconds");
		
		options.addOption("vv", "verbose", true, "Verbose logging");
				
		return options;
	}

	public void printHelp(final int printedRowWidth, final int spacesBeforeOption, final int spacesBeforeOptionDescription, final boolean displayUsage, final OutputStream out) {
		final String commandLineSyntax = "java " + this.getClass().getCanonicalName();
		final PrintWriter writer = new PrintWriter(out);
		final HelpFormatter helpFormatter = new HelpFormatter();
		helpFormatter.printHelp(writer, printedRowWidth, commandLineSyntax, HELP_MSG_HEADER, options, spacesBeforeOption, spacesBeforeOptionDescription, HELP_MSG_FOOTER, displayUsage);
		writer.flush();
	}
}