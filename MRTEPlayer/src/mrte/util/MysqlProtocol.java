package mrte.util;

import java.sql.SQLException;



/**
 * MySQL Client/Server Protocol
 * 
 * @see https://dev.mysql.com/doc/internals/en/client-server-protocol.html
 * @author matt
 *
 */
public class MysqlProtocol {
	public final static int SEQUENCE_FOR_LOGIN = 0x01;
	
	public final static int COM_QUIT           = 0x01;
	public final static int COM_INIT_DB        = 0x02;
	public final static int COM_QUERY          = 0x03;
	public final static int COM_SET_OPTION     = 0x1b; /* =27 */
	public final static int COM_LOGIN          = 0x7e; /* =126, This is artificial command */
	public final static int COM_UNKNOWN        = 0x7f; /* =127, This is artificial command */

	public final static String NETWORK_CHARACTERSET = "UTF-8";
	
	public final static int FLAG_CLIENT_LONG_PASSWORD     = 1;       // new more secure passwords
	public final static int FLAG_CLIENT_FOUND_ROWS        = 2;       // Found instead of affected rows
	public final static int FLAG_CLIENT_LONG_FLAG         = 4;       // Get all column flags
	public final static int FLAG_CLIENT_CONNECT_WITH_DB   = 8;       // One can specify db on connect
	public final static int FLAG_CLIENT_NO_SCHEMA         = 16;      // Don't allow database.table.column
	public final static int FLAG_CLIENT_COMPRESS          = 32;      // Can use compression protocol
	public final static int FLAG_CLIENT_ODBC              = 64;      // Odbc client
	public final static int FLAG_CLIENT_LOCAL_FILES       = 128;     // Can use LOAD DATA LOCAL
	public final static int FLAG_CLIENT_IGNORE_SPACE      = 256;     // Ignore spaces before '('
	public final static int FLAG_CLIENT_PROTOCOL_41       = 512;     // New 4.1 protocol
	public final static int FLAG_CLIENT_INTERACTIVE       = 1024;    // This is an interactive client
	public final static int FLAG_CLIENT_SSL               = 2048;    // Switch to SSL after handshake
	public final static int FLAG_CLIENT_IGNORE_SIGPIPE    = 4096;    // IGNORE sigpipes
	public final static int FLAG_CLIENT_TRANSACTIONS      = 8192;    // Client knows about transactions
	public final static int FLAG_CLIENT_RESERVED          = 16384;   // Old flag for 4.1 protocol
	public final static int FLAG_CLIENT_SECURE_CONNECTION = 32768;   // New 4.1 authentication
	public final static int FLAG_CLIENT_MULTI_STATEMENTS  = 65536;   // Enable/disable multi-stmt support
	public final static int FLAG_CLIENT_MULTI_RESULTS     = 131072;  // Enable/disable multi-results
	public final static int FLAG_CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA = 0x00200000; // understands length encoded integer for auth response data in Protocol::HandshakeResponse41
	
	public final static String SQLSTATE_NO_INIT_DB = "3D000";
	public final static String SQLSTATE_DUP_KEY = "23000";
	public final static String SQLSTATE_DEADLOCK = "40001";
	// ERROR 1146 (42S02): Table 'syncdb.abc' doesn't exist
	public final static String SQLSTATE_TABLE_NOTFOUND = "42S02";
	// ERROR 1054 (42S22): Unknown column 'z_user.mtt' in 'field list'
	public final static String SQLSTATE_UNKNOWN_COLUMN = "42S22";
	
	public final static int    SQLERROR_LOCK_WAIT_TIMEOUT = 1205;
	public final static int    SQLERROR_TABLE_NOTFOUND = 1146;
	public final static int    SQLERROR_UNKNOWN_COLUMN = 1054;
	
	
	public static String parseInitDatabaseName(byte[] loginData) throws Exception{
		int currPosition = 0;
		String databaseName = null;
		
		// loginData = sequence(1byte) + login-data
		if(loginData==null || loginData.length<=5){
			throw new Exception("COM_CONNECT packet's length must be greater than 5, current packet length is " + loginData.length);
		}
		
		/**
         *     4              capability flags, CLIENT_PROTOCOL_41 always set
         *     4              max-packet size
         *     1              character set number
         *     string[23]     reserved (all [0]) -> (filler) always 0x00...
         *     string[NUL]    username
         * if capabilities & CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA {
         *     lenenc-int     length of auth-response
         *     string[n]      auth-response
         * } else if capabilities & CLIENT_SECURE_CONNECTION {
         *     1              length of auth-response
         *     string[n]      auth-response
         * } else {
         *     string[NUL]    auth-response
         * }
         * if capabilities & CLIENT_CONNECT_WITH_DB {
         *     string[NUL]    database
         * }
         * if capabilities & CLIENT_PLUGIN_AUTH {
         *     string[NUL]    auth plugin name
         * }
         * if capabilities & CLIENT_CONNECT_ATTRS {
         *     lenenc-int     length of all key-values
         *     lenenc-str     key
         *     lenenc-str     value
         * if-more data in 'length of all key-values', more keys and value pairs
         * }
		 */
		
//		byte[] capabilityFlag = new byte[4];
//		capabilityFlag[0] = loginData[currPosition++];
//		capabilityFlag[1] = loginData[currPosition++];
//		capabilityFlag[2] = loginData[currPosition++];
//		capabilityFlag[3] = loginData[currPosition++];
//		
		int flag = (int)ByteHelper.readUnsignedIntLittleEndian(loginData, 0);
		currPosition = 4; // capabilityFlag - 4 bytes;
		currPosition += (4/*MaxPacketSize*/+1/*CharacterSet*/+23/*Reserved*/);
		
		try{
			// Read user name
			byte[] userName = ByteHelper.readNullTerminatedBytes(loginData, currPosition);
			// Read auth response
			currPosition += (userName.length+1/*NULL-TERMINATION*/);
			if((flag & FLAG_CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA) == FLAG_CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA){
				long[] lengths = ByteHelper.readLengthCodedBinary2(loginData, currPosition);
				currPosition += (lengths[0]/*length-bytes*/ + lengths[1]/*data-bytes*/);
			}else if((flag & FLAG_CLIENT_SECURE_CONNECTION) == FLAG_CLIENT_SECURE_CONNECTION){
				int length = loginData[currPosition];
				currPosition += (1 + length);
			}else{
				byte[] authResponse = ByteHelper.readNullTerminatedBytes(loginData, currPosition);
				currPosition += (authResponse.length+1/*NULL-TERMINATION*/);
			}
			
			if((flag & FLAG_CLIENT_CONNECT_WITH_DB) == FLAG_CLIENT_CONNECT_WITH_DB){
				System.out.println("--- pos : " + currPosition + " : FLAG_CLIENT_CONNECT_WITH_DB");
				byte[] initDatabase = ByteHelper.readNullTerminatedBytes(loginData, currPosition);
				databaseName = (initDatabase!=null && initDatabase.length>0) ? new String(initDatabase) : null;
				currPosition += (1 + initDatabase.length);
				//System.out.println(">> Database : " + databaseName);
			}else{
				//System.out.println(">> Without Database");
			}
			
//			if((flag & FLAG_CLIENT_PLUGIN_AUTH) == FLAG_CLIENT_PLUGIN_AUTH){
//				byte[] authPluginName = ByteHelper.readNullTerminatedBytes(loginData, currPosition);
//				String authPlugin = (authPluginName!=null && authPluginName.length>0) ? new String(authPluginName) : null;
//				//System.out.println(">> Database : " + databaseName);
//			}else{
//				//System.out.println(">> Without Database");
//			}
		}catch(Exception ex){
			if(ex instanceof ByteReadException){
				ByteReadException bre = (ByteReadException)ex;
				System.err.println("---------------------------------------------------------------------");
				System.err.println("ByteReadException("+bre.totalDataLength+","+bre.startPoint+","+bre.needLength+")-------");
				HexHelper.dumpBytes(System.err, loginData);
				System.err.println("---------------------------------------------------------------------");
				
				databaseName = null;
			}
		}
		
		return databaseName;
	}
	

	public static String getTablenameFromExceptionMessage(SQLException ex){
		String state = ex.getSQLState();
		int errno = ex.getErrorCode();
		
		if(MysqlProtocol.SQLSTATE_TABLE_NOTFOUND.equalsIgnoreCase(state) && MysqlProtocol.SQLERROR_TABLE_NOTFOUND==errno){
			// ERROR 1146 (42S02): Table 'syncdb.abc' doesn't exist
			// message = "Table 'syncdb.abc' doesn't exist"
			String message = ex.getMessage();
			int startPos = message.indexOf('\'');
			if(startPos<=0) return null;
			
			if(++startPos >= message.length()) return null;
			int endPos = message.indexOf('\'', startPos);
			if(endPos<=startPos) return null;
			
			String dbTableName = message.substring(startPos, endPos);
			String[] dbAndTable = dbTableName.split("\\.");
			if(dbAndTable.length<2){
				return null;
			}
			
			return dbAndTable[1];
		}else if(MysqlProtocol.SQLSTATE_UNKNOWN_COLUMN.equalsIgnoreCase(state) && MysqlProtocol.SQLERROR_UNKNOWN_COLUMN==errno){
			// ERROR 1054 (42S22): Unknown column 'z_user.mtt' in 'field list'
			// message = "Unknown column 'z_user.mtt' in 'field list'"
			String message = ex.getMessage();
			int startPos = message.indexOf('\'');
			if(startPos<=0) return null;
			
			if(++startPos >= message.length()) return null;
			int endPos = message.indexOf('\'', startPos+1);
			if(endPos<=startPos) return null;
			
			String dbTableName = message.substring(startPos, endPos);
			String[] dbAndTable = dbTableName.split("\\.");
			if(dbAndTable.length<2){
				return null;
			}
			
			return dbAndTable[0];
		}
		
		return null;
	}
	
	

	
	/**
	 * mongo> var binData = BinData(0,"jaK/gQAAAEAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABtcnRlMgAU2KWkzLkOSITLkFahevYgcwcCZN1teXNxbHNsYXAAbXlzcWxfbmF0aXZlX3Bhc3N3b3JkAFcDX29zBUxpbnV4DF9jbGllbnRfbmFtZQhsaWJteXNxbARfcGlkBjExMzIxNg9fY2xpZW50X3ZlcnNpb24JNS43LjIyLTIyCV9wbGF0Zm9ybQZ4ODZfNjQ=");
     * mongo> var hex = binData.hex();
     * mongo> print(hex);
     * 8da2bf81000000400800000000000000000000000000000000000000000000006d727465320014d8a5a4ccb90e4884cb9056a17af62073070264dd6d7973716c736c6170006d7973716c5f6e61746976655f70617373776f72640057035f6f73054c696e75780c5f636c69656e745f6e616d65086c69626d7973716c045f706964063131333231360f5f636c69656e745f76657273696f6e09352e372e32322d3232095f706c6174666f726d067838365f3634
     * 
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception{
		String hex = "85a2bf81000000400800000000000000000000000000000000000000000000006d727465320014408d6ae97e1841835a8a5d5a6d9b5a1a01f017cb6d7973716c5f6e61746976655f70617373776f7264006d035f6f73054c696e75780c5f636c69656e745f6e616d65086c69626d7973716c045f7069640533373532360f5f636c69656e745f76657273696f6e09352e372e32322d3232095f706c6174666f726d067838365f36340c70726f6772616d5f6e616d65096d7973716c736c6170";
		byte[] binData = HexHelper.hexStringToByteArray(hex);
		
		HexHelper.dumpBytes(System.out, binData);
				
		String dbName = MysqlProtocol.parseInitDatabaseName(binData);
		System.out.println(dbName);
	}
}
