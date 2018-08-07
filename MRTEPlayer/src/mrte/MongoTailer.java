package mrte;

import mrte.util.MysqlProtocol;

import org.bson.Document;
import org.bson.types.ObjectId;

import com.mongodb.BasicDBObject;
import com.mongodb.CursorType;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

public class MongoTailer /* extends Thread*/ {
	MRTEPlayer parent;
	String uri;
	String dbName;
	String collectionPrefix;
	String[] collections;
	int partitions;
	
	MongoClient client;
	CappedCollectionTailer[] threads;
	
	public MongoTailer(MRTEPlayer parent, String uri, String db, String collectionPrefix, int partitions){
		this.parent = parent;
		this.uri = uri;
		this.dbName = db;
		this.collectionPrefix = collectionPrefix;
		this.partitions = partitions;
		
		this.threads = new CappedCollectionTailer[this.partitions]; 
		this.collections = new String[this.partitions];
		for(int idx=0; idx<this.partitions; idx++){
			this.collections[idx] = this.collectionPrefix + String.format("%03d", idx);
		}
	}

	protected MongoClient getConnection(String uri /*String host, int port, String database, String user, String password*/)
			throws Exception {
		// http://mongodb.github.io/mongo-java-driver/3.2/driver/reference/connecting/authenticating/
		// MongoClientURI uri = new MongoClientURI("mongodb://" + user + ":" + password + "@" + host + ":" + port
		//		+ "/?authSource=" + database + "&authMechanism=SCRAM-SHA-1");
		MongoClientURI connectionString = new MongoClientURI(uri);
		MongoClient mongo = new MongoClient(connectionString);

		return mongo;
	}
	
	public void tailMysqlRequest() throws Exception{
		this.client = getConnection(this.uri);
		this.run1();
	}
	
	public void run1(){
		for(int idx=0; idx<this.collections.length; idx++){
			System.out.println("Log Tailer " + (idx+1) + " : Preparing capped collection tailer thread with db("+this.dbName+") and collection("+collections[idx]+")");
			threads[idx] = new CappedCollectionTailer(this.parent, (idx+1), client.getDatabase(this.dbName).getCollection(collections[idx]));
			threads[idx].start();
		}
		
//		while(true){
// Threads[idx].join(1000)이 1초후 exception 없이 반환되는데, 그 이후 다시 쓰레드가 생성됨.
// Threads[idx].alive()와 같은 방식으로 체크해야 하는데, 안정성을 모르겠음.
//			for(int idx=0; idx<threads.length; idx++){
//				try{
//					threads[idx].join(1000);
//					ObjectId lastExecuted = threads[idx].getLastExecuted();
//					// Refresh mongo connection
//					//   client.close();
//					//   client = getConnection(this.uri);
//					// TODO idx thread is killed, so restart threads[idx] again					
//					threads[idx] = new CappedCollectionTailer(this.parent, (idx+1), client.getDatabase(this.dbName).getCollection(collections[idx]), lastExecuted);
//					System.out.println("Log Tailer " + (idx+1) + " : Restart tailing capped collection");
//				}catch(Exception exception){
//					// Ignore
//				}
//			}
//		}
	}
}

class CappedCollectionTailer extends Thread{
	MRTEPlayer parent;
	int tailerId;
	MongoCollection<Document> collection;
	
	ObjectId lastExecuted;
	
	public CappedCollectionTailer(MRTEPlayer parent, int tid, MongoCollection<Document> collection){
		this.parent = parent;
		this.tailerId = tid;
		this.collection = collection;
		this.lastExecuted = null;
	}
	
	public CappedCollectionTailer(MRTEPlayer parent, int tid, MongoCollection<Document> collection, ObjectId lastExecuted){
		this.parent = parent;
		this.tailerId = tid;
		this.collection = collection;
		this.lastExecuted = lastExecuted;
	}
	
	public ObjectId getLastExecuted(){
		return this.lastExecuted;
	}
	
	public void run(){
		int retryCounter = 0;
		MongoCursor<Document> cursor = null;
		
		while(true){
			try{
				if(this.lastExecuted==null){
					cursor = this.collection.find().sort(new BasicDBObject("$natural", 1)).cursorType(CursorType.TailableAwait).noCursorTimeout(true).iterator();
				}else{
					Document query = new Document("_id", new Document("$gt", this.lastExecuted));
					cursor = this.collection.find(query).sort(new Document("$natural", 1)).cursorType(CursorType.TailableAwait).noCursorTimeout(true).iterator();
				}
				retryCounter = 0;
				Document doc = null;
				MysqlRequest request = null;
				while(cursor.hasNext()){
					doc = cursor.next();
					this.lastExecuted = doc.getObjectId("_id");
					parent.recvPacketCounter.incrementAndGet();
					request = new MysqlRequest(doc);

					if(request.command==MysqlProtocol.COM_UNKNOWN){ // Ignoring unknown-command including INITIAL-MARKER for MongoDB CappedCollection
						continue;
					}
					
					parent.pushEvent2Player(request);
				}
			}catch(Exception ex){
				ex.printStackTrace(System.err);
				if(retryCounter>50){
					parent.abort("Can not connect to MongoDB server"); // exit thread (Assume it can't be recoverable
				}
				retryCounter++;
			}finally{
				if(cursor!=null){
					try{
						cursor.close();
					}catch(Exception ignore){}
				}
			}
		}
	}
}