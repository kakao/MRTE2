package mrte;

import mrte.util.HexHelper;
import mrte.util.MysqlProtocol;

import org.bson.Document;

public class MysqlRequest {
	public final String _id;
	public final String ip;
	public final int port;
	public final int command;
	public final String statement;
	
	private MysqlRequest(){
		this._id = "";
		// Generate Quit request
		this.ip = "";
		this.port = 0;
		this.command = MysqlProtocol.COM_QUIT;
		this.statement = "";
	}
	
	public MysqlRequest(Document ev){
		this._id = (ev.getObjectId("_id")==null) ? "" : ev.getObjectId("_id").toHexString();
		this.ip = ev.getString("ip");
		this.port = ev.getInteger("port", 1);
		Document evDetail = (Document)ev.get("req");
		int cmd = evDetail.getInteger("c", MysqlProtocol.COM_UNKNOWN);
		String stmt = null;
		try{
			if(cmd==MysqlProtocol.COM_LOGIN){
				cmd = MysqlProtocol.COM_INIT_DB;
				
				String hex = evDetail.getString("q");
				byte[] loginData = (hex==null || hex.length()<2) ? null : HexHelper.hexStringToByteArray(hex);
				if(loginData==null){
					stmt = "";
				}else{
					String defaultDatabase;
					try{
						defaultDatabase = MysqlProtocol.parseInitDatabaseName(loginData);
					}catch(Exception ex){
						defaultDatabase = "";
						System.err.println("Error during parse login data(_id="+this._id+")");
						ex.printStackTrace(System.err);
					}
					stmt = (defaultDatabase==null) ? "" : defaultDatabase;
				}
			}else{
				stmt = evDetail.getString("q");
			}
		}catch(Exception ex){
			System.err.println("Error during parse mysql payload(_id="+this._id+") --> MysqlRequest, Command("+cmd+"), Type(" + evDetail.get("q").getClass().getSimpleName() + ")");
			ex.printStackTrace(System.err);
		}finally{
			this.command = cmd;
			this.statement = stmt;
		}
	}
	
	public static MysqlRequest generateQuitRequest(){
		return new MysqlRequest();
	}
}
