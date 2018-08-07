package mrte

import (
	"log"
	"fmt"
	"time"
	"github.com/globalsign/mgo"
	"github.com/globalsign/mgo/bson"
)

/**
 * MongoDB go driver 사용 방법은 아래 URL 참조
 * ref https://qiita.com/rubytomato@github/items/4192e2340d81c0c39ca5
 */

/**
 * 캡쳐된 packet에서 쿼리 실행을 위해서 필요한 정보들만 구조체로 정의
 */
type MysqlRequest struct {
	// 이미 패킷 분석이 되었는지 여부
	// AlreadyParsed==true이면, 이는 실제 캡쳐된 패킷이 아니라, 이 프로그램에서 인위적으로 생성한 패킷임 (그리고 이 경우 Data 필드이 값이 NULL임)
	// AlreadyParsed==false이면, 실제 캡쳐된 패킷이며, Data 필드가 NOT NULL임 
	AlreadyParsed bool
	
	// 요청을 발생시킨 Client의 IpAddress와 Port
	// (SourceIp + SourcePort) 조합은 MRTECollector에서 패킷 어셈블의 기준임과 동시에 multi-thread 처리의 기준이며,
	//                              MRTEPlayer에서는 SQLPlayer객체를 할당하는 기준임 
	SrcIp   string
	SrcPort int32  // port는 uint16 타입이지만, Java나 MongoDB 연동을 위해서 int32 타입으로 관리한다.
	
	// MySQL payload 이진 데이터 (Parsing되기 전이며, 이 데이터가 파싱되어서 ParsedRequest로 변환됨)
	// Data 필드의 값은 MongoDB로 저장되지 않고, ParsedRequest 객체가 MongoDB로 저장됨
	Data []byte
	
	CapturedAt time.Time
	
	// ParseRequest는 Sub-Document로 "q" 필드와 "c" 필드를 가짐
	//  - c : MySQL Packet에서 command (int 타입)
	//  - q : MySQL Packet에서 data (string 타입)
	//        connection 패킷인 경우, []byte 타입의 값을 HexString으로 변환해서 MongoDB에 저장
	//        text-protocol 패킷인 경우, Client가 실행 요청한 Statement를 그대로 string으로 MongoDB에 저장 
	ParsedRequest *bson.M
}

/**
 * MongoDB 서버 연결 정보
 */
var connection *mgo.Session

/**
 * MongoDB 연결 생성
 */
func InitConnection(uri string) *mgo.Session{
	var err error
    
    // MongoDB 접속 URI 포맷
    // uri <= mongodb://userid:password@127.0.0.1:27017?connect=direct
	if connection, err = mgo.Dial(uri); err != nil {
		log.Fatal(err)
	}
	
	return connection
}

/**
 * MongoDB 컨넥션에서 세션을 생성
 * MongoDB로 쿼리를 실행하기 위해서는, 반드시 InitConnection()을 먼저 호출해서 connection 객체를 초기화한 후,
 * GetSession()을 호출해서 세션을 생성해서 쿼리를 실행해야 한다.
 */
func GetSession() *mgo.Session {
	session := connection.Clone()
	return session
}





/**
 * 주어진 Prefix와 시퀀스 번호(샤딩된 컬렉션 번호)를 이용해서 컬렉션의 full name 생성
 */
func getCollectionName(prefix string, idx int) string {
	// 시퀀스 번호(샤딩된 컬렉션 번호)는 "0"으로 패딩해서 3자리 숫자로 표시
	return fmt.Sprintf("%s%03d", prefix, idx)
}

/**
 * 주어진 이름의 컬렉션이 존재하면 해당 컬렉션을 삭제하고,
 * 주어진 이름과 사이즈를 기준으로 CappedCollection을 생성
 */
func PrepareCollections(dbName string, prefix string, maxSize int, counter int){
	for idx := 0; idx < counter; idx++ {
		reCreateCappedCollection(dbName, getCollectionName(prefix, idx), maxSize)
	}
}

func reCreateCappedCollection(dbName string, collectionName string, maxSize int){
	session := GetSession()
	defer session.Close()
	
	collection := session.DB(dbName).C(collectionName)

	// 우선 동일 이름의 컬렉션이 있다면 먼저 삭제
	collection.DropCollection()
	
	// 주어진 사이즈로 Capped Collection 생성
	collectionInfo := &mgo.CollectionInfo{
		Capped:   true,
		MaxBytes: maxSize,
	}
	
	err := collection.Create(collectionInfo)
	if err != nil {
		fmt.Printf("%+v \n", err)
	}

	// CappedCollection이 생성되면, 초기에 1건의 샘플 데이터를 저장한다.
	// CappedCollection에 레코드가 한건도 없으면, MRTEPlayer의 TailableCursor의 데이터 읽기가 계속 실패하게 된다.
	// 그래서 최소 한건의 데이터는 CappedCollection에 있어야 하므로, 테이블 재생성되면 샘플 데이터 1건을 저장한다.
	// 여기에서 저장되는 데이터는 MRTEPlayer가 읽어가긴 하지만, 실제 Target MySQL 서버로 실행되진 않고 그냥 MRTEPlayer가 무시하게 된다. 
	// ** (아래 샘플 데이터의 내용 변경하지 말것) ** 
	err = collection.Insert(bson.M{"ip":"127.0.0.1", "port":0, "req":bson.M{"c":COM_UNKNOWN, "q":"Initial Marker for CappedCollection"}})
	if err != nil {
		fmt.Printf("%+v \n", err)
	}
}

/**
 * 파싱된 MySQL Packet을 MongoDB로 저장
 */
func InsertPackets(dbName string, collectionPrefix string, queueId int, packets []*MysqlRequest) int {
	p := make([]interface{}, len(packets))
	for idx, v := range packets {
		p[idx] = bson.M{"ip": v.SrcIp, "port": v.SrcPort, "req": v.ParsedRequest}
		// p[idx] = bson.M{"ip": v.SrcIp, "port": v.SrcPort, "payload": v.Data}
	}
	
	session := GetSession()
	defer session.Close()
	
	err := session.DB(dbName).C(getCollectionName(collectionPrefix, queueId)).Insert(p...)
	if err != nil {
  		fmt.Printf("%+v \n", err)
  		return 0 // 파라미터로 주어진 배열중에서 몇건이나 처리되었는지 따지지 않고, 그냥 처리 건수를 0건으로 반환
	}
	
	// 에러가 없으면, 배열의 모든 패킷이 성공적으로 저장된 것으로 간주
	return len(packets)
}

/**
 * 파싱된 MySQL Packet을 MongoDB로 Bulk 저장
 */
func InsertBulkPackets(dbName string, collectionPrefix string, queueId int, packets []*MysqlRequest) int {
        session := GetSession()
        defer session.Close()

        collection := session.DB(dbName).C(getCollectionName(collectionPrefix, queueId))
        bulk := collection.Bulk()
        for _, v := range packets{
                bulk.Insert(bson.M{"ip": v.SrcIp, "port": v.SrcPort, "req": v.ParsedRequest})
        }

        // var bulkRes *mgo.BulkResult
        var err error
        _/*bulkRes*/, err = bulk.Run()
        if err != nil {
                fmt.Printf("Bulk insert to Queue(MongoDB) failed : %+v \n", err)
                return 0 // 파라미터로 주어진 배열중에서 몇건이나 처리되었는지 따지지 않고, 그냥 처리 건수를 0건으로 반환
        }

        // 에러가 없으면, 배열의 모든 패킷이 성공적으로 저장된 것으로 간주
        return len(packets)
}