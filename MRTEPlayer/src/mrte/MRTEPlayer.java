package mrte;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import mrte.util.CommandLineOption;
import mrte.util.DatabaseMapper;
import mrte.util.MysqlProtocol;

public class MRTEPlayer {
	public static final String PROGNAME = "MRTEPlayer";
	public static final String VERSION  = "2.0.0";

	
	public boolean verbose = false;
	
	int    targetDatabaseInitConnections = 300;
	String targetDatabaseUri; // MySQL JDBC URL
	String mongoDatabaseUri;  // MongoDB URL (packet queue)
	String mongoDatabase;
	String mongoCollectionPrefix;
	int    mongoCollections;
	
	/**
	 * if defaultDatabase==null then, MRTEPlayer will guess default db from query statement,
	 * else MRTEPlayer will not guess default db and just use it.
	 */
	String targetDatabaseDefaultDatabase;
	/**
	 * targetDatabaseForceDefaultDB=true이면, 
	 * MRTECollector를 통해서 전달된 change db 명령이나 Connection 생성시점의 init_db 옵션등을 모두 무시하고
	 * targetDatabaseDefaultDatabase를 강제로 사용하도록 한다.
	 */
	boolean targetDatabaseForceDefaultDB;
	long connectionPreparedTs;
	ConcurrentLinkedQueue<Connection> preparedConnQueue;
	
	long slowQueryTime = 1000 /* Milli-seconds */;
	long maxAllowedPacketSize;
	
	DatabaseMapper dbMapper = null;
	
	Map<String, SQLPlayer> playerMap = new HashMap<String, SQLPlayer>();
	Map<String, List<String>> tableDbMap = new HashMap<String, List<String>>(); // To find db from tablename.
	
	
	// status metric
	private final int STATUS_INTERVAL_SECOND = 1;
	private SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");	
	
	public AtomicLong recvPacketCounter = new AtomicLong(0);
	public AtomicLong errorPacketCounter = new AtomicLong(0);
	public AtomicLong debugStatCounter = new AtomicLong(0); // Debugging
	public AtomicLong playerErrorCounter = new AtomicLong(0);
	public AtomicLong newSessionCounter = new AtomicLong(0);
	public AtomicLong closeSessionCounter = new AtomicLong(0);
	public AtomicLong userRequestCounter = new AtomicLong(0);
	public AtomicLong duplicateKeyCounter = new AtomicLong(0);
	public AtomicLong deadlockCounter = new AtomicLong(0);
	public AtomicLong lockTimeoutCounter = new AtomicLong(0);
	public AtomicLong noInitDatabsaeCounter = new AtomicLong(0);
	public AtomicLong longQueryCounter = new AtomicLong(0);
	
	static{
		Logger mongoLogger = Logger.getLogger( "org.mongodb.driver" );
		mongoLogger.setLevel(Level.SEVERE);
	}
	
	public static void main(String[] args) throws Exception{
        // Print version
        System.out.println(PROGNAME + " - " + VERSION);
        
		MRTEPlayer player = new MRTEPlayer();
		
		CommandLineOption options = new CommandLineOption(args);
		
		try{
			player.targetDatabaseUri = options.getStringParameter("mysql_url");
			player.targetDatabaseInitConnections = options.getIntParameter("mysql_init_connections", 300);
			player.targetDatabaseForceDefaultDB = options.getBooleanParameter("mysql_force_default_db", false);
			player.targetDatabaseDefaultDatabase = options.getStringParameter("mysql_default_db", null);
			if(player.targetDatabaseDefaultDatabase==null || player.targetDatabaseDefaultDatabase.trim().length()<=0){
				player.targetDatabaseDefaultDatabase = null;
				player.targetDatabaseForceDefaultDB = false;  // default db가 없으므로, 강제로 고정할 DB가 없어서 targetDatabaseForceDefaultDB 옵션을 FALSE로 재조정한다.
				System.err.println(">> mysql_force_default_db parameter must be FALSE if mysql_default_db option is empty. Assuming mysql_force_default_db as FALSE");
			}

			player.mongoDatabaseUri = options.getStringParameter("mongo_url");
			player.mongoCollectionPrefix = options.getStringParameter("mongo_collectionprefix");
			player.mongoDatabase = options.getStringParameter("mongo_db");
			player.mongoCollections = options.getIntParameter("mongo_collections");
			
			player.slowQueryTime = options.getLongParameter("slow_query_time", 1000);
			
			String dbMapOption = options.getStringParameter("database_remap", null);
			if(dbMapOption!=null && dbMapOption.trim().length()>0){
				player.dbMapper = new DatabaseMapper();
				player.dbMapper.parseDatabaseMapping(options.getStringParameter("database_remap"));
			}
			
			player.maxAllowedPacketSize = options.getLongParameter("max_packet_size", SQLPlayer.MAX_ALLOWED_PACKET_LEN);
			
			player.verbose = options.getBooleanParameter("verbose", false);
		}catch(Exception ex){
			ex.printStackTrace();
			options.printHelp(80, 5, 3, true, System.out);
			return;
		}

		player.runMRTEPlayer();
	}
	
	
	protected void runMRTEPlayer() throws Exception{
		// prepare target mysql connection
		this.prepareConnections();
		
		// init mongodb consumer connection
		System.out.println("    >> MRTEPlayer :: Preparing mongodb connection and capped collection tailer");
		MongoTailer tailer = new MongoTailer(this, this.mongoDatabaseUri, this.mongoDatabase, this.mongoCollectionPrefix, this.mongoCollections);
		tailer.tailMysqlRequest();

		System.out.println("    >> MRTEPlayer :: Waiting for user request events");
	    // Print status
	    long pRecvPacketCounter = 0;
	    long pErrorPacketCounter = 0;
	    long pNewSessionCounter = 0;
	    long pCloseSessionCounter = 0;
	    long pUserRequestCounter = 0;
	    long pPlayerErrorCounter = 0;
	    long pDuplicateKeyCounter = 0;
	    long pDeadlockCounter = 0;
	    long pLockTimeoutCounter = 0;
	    long pNoInitDatabsaeCounter = 0;
	    long pLongQueryCounter = 0;
	    //long pDebugStatCounter = 0;

	    long cRecvPacketCounter = 0;
	    long cErrorPacketCounter = 0;
	    long cNewSessionCounter = 0;
	    long cCloseSessionCounter = 0;
	    long cUserRequestCounter = 0;
	    long cPlayerErrorCounter = 0;
	    long cDuplicateKeyCounter = 0;
	    long cDeadlockCounter = 0;
	    long cLockTimeoutCounter = 0;
	    long cNoInitDatabsaeCounter = 0;
	    long cLongQueryCounter = 0;
	    //long cDebugStatCounter = 0;
	    
	    int loopCounter = 0;
	    while(true){
	    	if(loopCounter%5==0){
	    		System.out.println();
	    		System.out.println("DateTime                TotalPacket      ErrorPacket   NewSession   ExitSession      UserRequest(Slow)        Error (NoInitDB  Duplicated  Deadlock  LockTimeout)");
	    		loopCounter = 0;
	    	}
	    	
	    	cRecvPacketCounter = recvPacketCounter.get();
	    	cErrorPacketCounter = errorPacketCounter.get();
	    	cNewSessionCounter = newSessionCounter.get();
	    	cCloseSessionCounter = closeSessionCounter.get();
	    	cUserRequestCounter = userRequestCounter.get();
	    	cPlayerErrorCounter = playerErrorCounter.get();
	    	cDuplicateKeyCounter = duplicateKeyCounter.get();
	    	cDeadlockCounter = deadlockCounter.get();
	    	cLockTimeoutCounter = lockTimeoutCounter.get();
	    	cNoInitDatabsaeCounter = noInitDatabsaeCounter.get();
	    	cLongQueryCounter = longQueryCounter.get();
	    	//cDebugStatCounter = debugStatCounter.get();
	    	
	    	System.out.format("%s %15d  %15d   %10d    %10d  %15d(%4d)  %11d (%8d  %10d  %8d  %11d)\n", dateFormatter.format(new Date()), 
	    			(long)((cRecvPacketCounter - pRecvPacketCounter) / STATUS_INTERVAL_SECOND),
	    			// (long)((cErrorPacketCounter - pErrorPacketCounter) / STATUS_INTERVAL_SECOND),
	    			(cErrorPacketCounter - pErrorPacketCounter), // Print sum for error count not average / sec
	    			(cNewSessionCounter - pNewSessionCounter),
	    			(cCloseSessionCounter - pCloseSessionCounter),
	    			(long)((cUserRequestCounter - pUserRequestCounter) / STATUS_INTERVAL_SECOND),
	    			(long)((cLongQueryCounter - pLongQueryCounter) / STATUS_INTERVAL_SECOND),
	    			(long)((cPlayerErrorCounter - pPlayerErrorCounter) / STATUS_INTERVAL_SECOND),
	    			(long)((cNoInitDatabsaeCounter - pNoInitDatabsaeCounter) / STATUS_INTERVAL_SECOND),
	    			(long)((cDuplicateKeyCounter - pDuplicateKeyCounter) / STATUS_INTERVAL_SECOND),
	    			(long)((cDeadlockCounter - pDeadlockCounter) / STATUS_INTERVAL_SECOND),
	    			(long)((cLockTimeoutCounter - pLockTimeoutCounter) / STATUS_INTERVAL_SECOND));
	    			// (long)((cDebugStatCounter - pDebugStatCounter) / STATUS_INTERVAL_SECOND));

	    	loopCounter++;
	    	pRecvPacketCounter = cRecvPacketCounter;
	    	pErrorPacketCounter = cErrorPacketCounter;
	    	pNewSessionCounter = cNewSessionCounter;
	    	pCloseSessionCounter = cCloseSessionCounter;
	    	pUserRequestCounter = cUserRequestCounter;
	    	pPlayerErrorCounter = cPlayerErrorCounter;
	    	pDuplicateKeyCounter = cDuplicateKeyCounter;
	    	pDeadlockCounter = cDeadlockCounter;
	    	pLockTimeoutCounter = cLockTimeoutCounter;
	    	pNoInitDatabsaeCounter = cNoInitDatabsaeCounter;
	    	pLongQueryCounter = cLongQueryCounter;
	    	//pDebugStatCounter = cDebugStatCounter;
	    	
			// Close all idle session 
			closeIdleSession();
	    	
	    	try{
	    		Thread.sleep(STATUS_INTERVAL_SECOND * 1000);
	    	}catch(Exception ignore){}
	    }
	}
	
	
	
	protected void pushEvent2Player(MysqlRequest req){
		// push to player-queue
		String sessionKey = MRTEPlayer.generateSessionKey(req.ip, req.port);
		
		SQLPlayer player = this.playerMap.get(sessionKey);
		if(req.command!=MysqlProtocol.COM_QUIT && player==null){
			// At starting MRTEPlayer, all connection is empty. So we have to guess default database with query
			player = new SQLPlayer(this, req.ip, req.port, this.preparedConnQueue.poll()/*Connection*/, this.targetDatabaseUri, this.targetDatabaseDefaultDatabase, this.targetDatabaseForceDefaultDB, this.slowQueryTime, this.maxAllowedPacketSize, this.tableDbMap);
			synchronized(this.playerMap){
				this.playerMap.put(sessionKey, player);
			}
			player.start();
			this.newSessionCounter.incrementAndGet();
			
			if(this.verbose){
				System.out.println("    >> SQLPlayer["+req.ip+":"+req.port+"] New connection created, query executed without sql player");
			}
		}
		
		if(player!=null){
			try{
				player.pushMysqlRequest(req);
			}catch(Exception ex){
				// MRTEPlayer can't follow captured request. Terminiate it.
				ex.printStackTrace(System.err);
				abort("Can not push request to queue of SQLPlayer");
			}
		}
		
		// If COM_QUIT, remove player from playMap
		if(req.command==MysqlProtocol.COM_QUIT && player!=null){
			synchronized(this.playerMap){
				this.playerMap.remove(sessionKey);
			}
			this.closeSessionCounter.incrementAndGet();
		}
	}
	
	public void abort(String message){
		System.err.println("MRTE Abort : " + message);
		System.exit(9);
	}
	
	protected void destroyPreparedConnections(){
		if( (System.currentTimeMillis() - this.connectionPreparedTs > 60*10*1000 /* 10 min */) && this.preparedConnQueue.size()>0){
			Connection conn1;
			while( (conn1 = this.preparedConnQueue.poll()) != null){
				try{
					conn1.close();
				}catch(Exception ignore){}
			}
		}
	}
	
	protected ConcurrentLinkedQueue<Connection> prepareConnections() throws Exception{
		Connection conn = null;
		ConcurrentLinkedQueue<Connection> connQueue = new ConcurrentLinkedQueue<Connection>();
		System.out.println("    >> MRTEPlayer :: Preparing target database connection");
		for(int idx=1; idx<=targetDatabaseInitConnections; idx++){
			conn = DriverManager.getConnection(this.targetDatabaseUri); // mysql://<username>:<password>@<host>:<port>/<db_name>
			connQueue.add(conn); // <--> poll(), poll will return null if no more item on queue
			System.out.print(".");
			if(idx%100 == 0){
				System.out.println(" --> Prepared " + idx + " connections");
			}
			try{
				Thread.sleep(50); // Give a sleep time for stable connection preparing
			}catch(Exception ignore){}
		}
		
		System.out.println("\n    >> MRTEPlayer :: Done preparing "+targetDatabaseInitConnections+" target database connection");
		this.preparedConnQueue = connQueue;
		this.connectionPreparedTs = System.currentTimeMillis(); // These connections will be closed when 10 minites ago from now.
		
		// Load all db and table list
		Statement stmt = null;
		ResultSet rs = null;
		if(conn!=null){
			try{
				stmt = conn.createStatement();
				rs = stmt.executeQuery("select table_schema, table_name from information_schema.tables");
				while(rs.next()){
					String dbName = rs.getString("table_schema");
					String tableName = rs.getString("table_name");
					if("sys".equalsIgnoreCase(dbName) || "performance_schema".equalsIgnoreCase(dbName) || "mysql".equalsIgnoreCase(dbName) || "information_Schema".equalsIgnoreCase(dbName)){
						continue;
					}
					
					List<String> dbList = tableDbMap.get(tableName);
					if(dbList==null){
						dbList = new ArrayList<String>();
						tableDbMap.put(tableName, dbList);
					}
					dbList.add(dbName);
				}
			}finally{
				if(rs!=null){try{rs.close();}catch(Exception ignore){}}
				if(stmt!=null){try{stmt.close();}catch(Exception ignore){}}
			}
		}
		return connQueue;
	}
	
	public static String generateSessionKey(String ipAddress, int port){
		return ipAddress + ":" + String.valueOf(port);
	}
	
	protected void closeIdleSession(){
		final int maxLoops = 10000;
		int loops = 0;
		long expireIdleTimestamp = System.currentTimeMillis() - 30*60*1000/* Idle time 30 minutes */;
		synchronized(this.playerMap){
			Iterator<Entry<String, SQLPlayer>> iter = this.playerMap.entrySet().iterator();
			while(iter.hasNext()){
				if(++loops > maxLoops){
					break;
				}
				
				Entry<String, SQLPlayer> entry = iter.next();
				SQLPlayer idleSQLPlayer = entry.getValue();
				if(idleSQLPlayer.getLastQueryExecuted() < expireIdleTimestamp){
					iter.remove();
					
					// Send terminiation signal to SQLPlayer
					idleSQLPlayer.pushUrgentMysqlRequest(MysqlRequest.generateQuitRequest());
					this.closeSessionCounter.incrementAndGet();
				}
			}
		}
	}
}
