package mrte;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import mrte.util.MysqlProtocol;

public class SQLPlayer extends Thread {
	public static final long MAX_ALLOWED_PACKET_LEN = 16*1024*1024-3; // 16MB
	public final static int SESSION_QUEUE_SIZE_DELAY = 5000;
	public final static int SESSION_QUEUE_SIZE_ABORT = 10000;
	public final static int DELAY_FOR_CONSUMING_QUEUE = 10; // milli seconds
	
	protected final MRTEPlayer parent;
	private boolean isShutdown = false;
	
	public final String clientIp;
	public final int clientPort;

	public final String jdbcUrl;
	public final long slowQueryTime; /* Milli-second */
	
	private ConcurrentLinkedQueue<MysqlRequest> requestQueue;
	private Connection targetConnection;
	private Statement stmt;
	private String currentDatabase;
	private boolean forceDefaultDatabase;
	private AtomicLong connectionOpened;
	private AtomicLong lastQueryExecuted;
	
	private Map<String, List<String>> tableDbMap;

	public SQLPlayer(MRTEPlayer parent, String clientIp, int clientPort, Connection conn, String jdbcUrl, String defaultDatabase, boolean forceDefaultDatabase, long slowQueryTime, long maxAllowedPacketSize, Map<String, List<String>> tableDbMap){
		this.parent = parent;
		this.clientIp = clientIp;
		this.clientPort = clientPort;
		
		this.jdbcUrl = jdbcUrl;
		
		this.slowQueryTime = slowQueryTime;
		
		this.connectionOpened = new AtomicLong(System.currentTimeMillis());
		this.lastQueryExecuted = new AtomicLong(System.currentTimeMillis());
		
		this.requestQueue = new ConcurrentLinkedQueue<MysqlRequest>(); // SQLPlayer.SESSION_QUEUE_SIZE);
		this.currentDatabase = defaultDatabase;
		this.forceDefaultDatabase = forceDefaultDatabase;
		
		this.targetConnection = conn;
		this.tableDbMap = tableDbMap;
	}
	

	public void run(){
		int retryCount = 0;
		while(retryCount++ < 10){
			try{
				if(this.targetConnection==null){
					Connection conn = getConnection();
					this.targetConnection = conn; 
				}
				initConnection(this.targetConnection);
				break;
			}catch(Exception ex){
				System.err.println(">> Can not open target database connection from SQLPlayer");
				ex.printStackTrace(System.err);
				if(retryCount>5){
					parent.abort("Can not open target database connection from SQLPlayer");
					return;
				}
				
				try{
					Thread.sleep(500);
				}catch(Exception ignore){}
			}
		}
		
		while(!isShutdown){
			try{
				MysqlRequest request = null;
		    	while(request==null){
		    		request = this.requestQueue.poll();
		    		if(request==null){
		    			try{
		    				Thread.sleep(5 /* Milli seconds */);
		    			}catch(Exception ignore){}
		    		}
		    	}
				
				// If something is comming, then refresh lastQueryExecuted timestamp
				this.lastQueryExecuted.set(System.currentTimeMillis());
				
				// Run query
				try{
					if(request.command == MysqlProtocol.COM_QUERY){
						try{
							long queryStart = System.currentTimeMillis();
							if(request.statement!=null && request.statement.length()>0){
								boolean hasResult = this.stmt.execute(request.statement);
								if(hasResult){
									ResultSet rs = this.stmt.getResultSet();
									// while(rs.next()); ==> MySQL always buffering all result set from server. so we don't need to iterating all rows.
									rs.close();
								}
							}
							long queryEnd = System.currentTimeMillis();
							
							if((queryEnd - queryStart) > this.slowQueryTime){
								parent.longQueryCounter.incrementAndGet();
							}
						}catch(Exception ex){
							parent.playerErrorCounter.incrementAndGet();
							if(ex instanceof SQLException){
								SQLException sqle = (SQLException)ex;
								String sqlState = sqle.getSQLState();
								int sqlErrorNo = sqle.getErrorCode();

								if((MysqlProtocol.SQLSTATE_TABLE_NOTFOUND.equalsIgnoreCase(sqlState) && MysqlProtocol.SQLERROR_TABLE_NOTFOUND==sqlErrorNo) ||
										(MysqlProtocol.SQLSTATE_UNKNOWN_COLUMN.equalsIgnoreCase(sqlState) && MysqlProtocol.SQLERROR_UNKNOWN_COLUMN==sqlErrorNo)){
									if(!this.forceDefaultDatabase){
										this.handleNotFoundException(sqle); // Auto resolve database from table name
									}
									parent.noInitDatabsaeCounter.incrementAndGet();
								}else if(MysqlProtocol.SQLSTATE_NO_INIT_DB.equalsIgnoreCase(sqlState)){
									// This will not happen if you set default_database.
									if(!this.forceDefaultDatabase){
										setDefaultDatabase(this.currentDatabase);
									}
									parent.noInitDatabsaeCounter.incrementAndGet();
								}else if(MysqlProtocol.SQLSTATE_DUP_KEY.equalsIgnoreCase(sqlState)){
									parent.duplicateKeyCounter.incrementAndGet();
								}else if(MysqlProtocol.SQLSTATE_DEADLOCK.equalsIgnoreCase(sqlState)){
									parent.deadlockCounter.incrementAndGet();
								}else if(MysqlProtocol.SQLERROR_LOCK_WAIT_TIMEOUT==sqlErrorNo){
									parent.lockTimeoutCounter.incrementAndGet();
								}else{
									System.out.println("    >> SQLPlayer["+this.clientIp+":"+this.clientPort+"] Failed to execute sql '"+request.statement+"'");
								}
							}
							//if(MRTEPlayer.IS_DEBUG){
							System.err.println("QUERY ERROR : Request._id : " + request._id);
								System.err.println("    >> SQLPlayer["+this.clientIp+":"+this.clientPort+"] COM_QUERY failed : " + ex.getMessage());
								System.err.println("       " + request.statement);
								ex.printStackTrace(System.err);
							//}
						}
						parent.userRequestCounter.incrementAndGet();
					}else if(request.command == MysqlProtocol.COM_INIT_DB && request.statement!=null && request.statement.length()>0){
						if(this.forceDefaultDatabase){
							continue;
						}
						
						boolean result = setDefaultDatabase(request.statement);
						if(!result){
							System.out.println("    >> SQLPlayer["+this.clientIp+":"+this.clientPort+"] set default db '"+request.statement+"' failed");
							parent.playerErrorCounter.incrementAndGet();
						}
						
						parent.userRequestCounter.incrementAndGet();
						if(parent.verbose){
							System.out.println("    >> SQLPlayer["+this.clientIp+":"+this.clientPort+"] Default db is initialized, and connection is prepared");
						}
					}else if(request.command == MysqlProtocol.COM_QUIT){
						// Stop replaying user request
						this.isShutdown = true;
						closeAllResource();
						break; // exit the main loop
					}else{
						System.out.println("    >> SQLPlayer Command is not handled : " + request.command);
					}
				}catch(Exception ex){
					ex.printStackTrace(System.err);
					parent.abort("Fatal player exception");
				}
			}catch(Exception ex){
				ex.printStackTrace(System.err);
				parent.abort("Fatal player exception");
			}
		}
	}
	
	
	protected Connection getConnection() throws Exception{
		Connection conn = DriverManager.getConnection(this.jdbcUrl);
		if(parent.verbose){
			System.out.println("    >> SQLPlayer["+this.clientIp+":"+this.clientPort+"] open connection");
		}
		return conn;
	}
	
	protected void initConnection(Connection conn) throws Exception{
		this.targetConnection = conn;
		this.stmt = this.targetConnection.createStatement();
		
		if(this.currentDatabase!=null && this.currentDatabase.length()>0){
			this.setDefaultDatabase(this.currentDatabase);
		}
		
		this.connectionOpened.set(System.currentTimeMillis());
	}
	
	protected void reconnect() throws Exception{
		if(checkConnection()){
			return;
		}
		
		if(this.stmt!=null){
			try{this.stmt.close();}catch(Exception ignore){}finally{this.stmt=null;}
		}
		if(this.targetConnection!=null){
			try{this.targetConnection.close();}catch(Exception ignore){}finally{this.targetConnection=null;}
		}
		
		System.err.println("      SQLPlayer :: create connection on reconnect()");
		initConnection(getConnection());
	}
	
	protected boolean checkConnection(){
		ResultSet rs = null;
		try{
			rs = stmt.executeQuery("SELECT 1");
			return true;
		}catch(Exception ex){
			
		}finally{
			if(rs!=null){try{rs.close();}catch(Exception ex){}}
		}
		
		return false;
	}
	
	protected void handleNotFoundException(SQLException ex){
		String tableName = MysqlProtocol.getTablenameFromExceptionMessage(ex);
		if(tableName==null || tableName.length()<=0){
			return;
		}
		
		List<String> dbNameList = this.tableDbMap.get(tableName);
		if(dbNameList==null || dbNameList.size()<=0){
			return;
		}
		
		for(int idx=0; idx<dbNameList.size(); idx++){
			if(this.currentDatabase==null || this.currentDatabase.length()<=0){
				setDefaultDatabase(dbNameList.get(idx));
				break;
			}else{
				if(!this.currentDatabase.equalsIgnoreCase(dbNameList.get(idx))){
					setDefaultDatabase(dbNameList.get(idx));
					break;
				}
			}
		}
	}
	
	protected boolean setDefaultDatabase(String dbName){
		if(this.forceDefaultDatabase){
			return true; // 사용자가 설정한 default database옵션을 새로운 값으로 덮어쓰지 않는다.
		}else if(dbName!=null){
			try{
				stmt.executeUpdate("USE " + dbName);
				this.currentDatabase = dbName;
				return true;
			}catch(Exception ex1){
				//if(MRTEPlayer.IS_DEBUG){
					System.err.println("Setting default database failed : '" + dbName + "'");
					ex1.printStackTrace(System.err);
				//}
				// ignore
			}
		}
		
		return false;
	}
	
	public void closeAllResource(){
		try{
			targetConnection.close();
		}catch(Exception ignore){}
	}
	
	public void pushMysqlRequest(MysqlRequest req) throws Exception{
		if(this.requestQueue.size() > SESSION_QUEUE_SIZE_ABORT){
			throw new Exception("Internal queue of sqlplayer is full (element size > " + SESSION_QUEUE_SIZE_ABORT + ")");
		}else if(this.requestQueue.size() > SESSION_QUEUE_SIZE_DELAY){
			System.err.println("Too many waiting event in the internal queue (element size > " + SESSION_QUEUE_SIZE_DELAY + ") - Add delay to push to queue");
			try{
				Thread.sleep(DELAY_FOR_CONSUMING_QUEUE);
			}catch(Exception ignore){}
		}
		this.requestQueue.offer(req);
	}
	
	public void pushUrgentMysqlRequest(MysqlRequest req){
		while(this.requestQueue.size()>0){
			this.requestQueue.poll();
		}
		this.requestQueue.offer(req);
	}
	
	public long getLastQueryExecuted(){
		return lastQueryExecuted.get();
	}
}
