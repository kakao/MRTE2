
### MRTECollector

#### Architecture

```
----------------------+------------------------------------------+-----------------------+
  Source MySQL        |             MRTECollector                |    Queue (MongoDB)    |
----------------------+------------------------------------------+-----------------------+
DBConnections[...] `                                                                      
DBConnections[...]  |                                                                     
DBConnections[...]  |                                                                     
DBConnections[...]  |                                                                     
DBConnections[...]  |                                                                     
DBConnections[...]  |           /- CHANEL[0] -> sendUserPacket() -> MongoDB(mrte.mrte000) 
DBConnections[...]  |          / - CHANEL[1] -> sendUserPacket() -> MongoDB(mrte.mrte001) 
DBConnections[...]  | -> pcap -  - CHANEL[2] -> sendUserPacket() -> MongoDB(mrte.mrte002) 
DBConnections[...]  |          ` - CHANEL[3] -> sendUserPacket() -> MongoDB(mrte.mrte003) 
DBConnections[...]  |           `- CHANEL[4] -> sendUserPacket() -> MongoDB(mrte.mrte004) 
DBConnections[...]  |                                                                     
DBConnections[...]  |                                                                     
DBConnections[...]  |                                                                     
DBConnections[...]  |                                                                     
DBConnections[...] /                                                                      
----------------------+------------------------------------------+-----------------------+
```


#### pcap
Ethernet card를 통해서 유입되는 네트워크 트래픽을 캡쳐해서, Source port값을 이용해서 주어진 개수의 채널(Golang channel)로 분산해서 전달한다.

MRTECollector는 pcap을 이용해서 패킷을 캡쳐할 때, MySQL 서버로 유입되는 패킷만 캡쳐하도록 BPF Filter를 설정한다.
```go
pcapFilter := fmt.Sprintf("tcp dst port %d", *port)
err = handle.SetFilter(pcapFilter)

```

GoLang에서 사용할 수 있는 packet capture 라이브러리는 여러가지가 있다. 그중에서 가장 대표적인 것이 Google에서 개발한 gopacket 라이브러리(https://github.com/google/gopacket)이다. gopacket 라이브러리는 여러가지 유연한 기능(packet assembly & layering decode)들을 제공하고 있어서, 처음에는 gopacket 라이브러리를 이용해서 MRTECollector를 개발했었다. 하지만 gopacket 라이브러리는 의외로 처리 성능이 낮았으며 Drop되는 패킷이 많았다. 그래서 최초의 MRTECollector에서 사용했던 miekg packet capture 라이브러리(https://github.com/miekg/pcap)를 그대로 재활용했다. 하지만 miekg 라이브러리도 MRTECollector에서는 불필요한 작업들을 많이 하면서 시스템 자원을 낭비해서, MRTECollector에는 변형된 miekg 라이브러리(miekg 개발자의 허락 득했으며, 자세한 내용은 src/github.com/miekg/pcap/README.md 파일을 참조)를 사용하고 있으며, 이로 인해서 miekg 라이브러리를 그대로 덮어서 빌드할 수는 없으며 반드시 MRTECollector에 내장된 miekg 라이브러리로 빌드해야 한다.

#### CHANNEL[...]
TCP Packet을 캡쳐하는 모듈은 매우 빠르게 처리해야 한다. 초당 30000개의 패킷이 유입된다면, 33 마이크로 초에 하나씩 패킷을 처리해 한다. 하지만 이 짧은 시간에 TCP 패킷을 분석하고 조립해서 원격지의 MongoDB 큐 컬렉션으로 저장하기에는 무리가 있다. 그래서 MRTECollector에서는 패킷을 캡쳐하는 모듈은 수집된 패킷에서 빠르게 Source port만 분석해서 여러개로 샤딩된 CHANNEL을 통해서 패킷 분석 모듈(sendUSerPacket func)로 전달한다. MRTECollecotr를 기동할 때 `--threads` 옵션으로 Channel의 샤드 개수와 패킷 분석 모듈(sendUSerPacket func)을 위한 쓰레드 개수를 조정할 수 있다. 또한 `-threads` 옵션에 의해서 MongoDB의 큐 컬렉션 개수도 결정된다.

뿐만 아니라, *MRTECollector의 `--threads` 옵션과 MRTEPlayer의 `--mongo_collections` 옵션은 동일 값으로 설정되어야 한다.*

#### sendUserPacket()
Channel을 통해서 전달받은 패킷을 분석해서 아래 3가지 정보를 추출한다.
- Source Ip
- Source port
- Application Layer(MySQL) payload

MySQL 서버의 payload는 전송되는 데이터의 크기에 따라서 그리고 운영체제의 네트워크 프레임 크기 설정에 따라서 여러 개의 TCP 패킷으로 분리되어서 전송될 수도 있다.
이런 경우에는 캡쳐한 패킷을 순서대로 다시 재조립(Assembly)해야 한다. 그래서 MRTECollector는 캡쳐한 패킷의 MySQL payload 크기와 MySQL payload header에 설정된 data size를 이용해서, TCP layer에서 여러 TCP 패킷으로 분리되었는지 아닌지를 판단한다. 
만약 하나의 MySQL 명령이 분리되지 않고 온전히 하나의 TCP 패킷으로 전달된 경우에는 그대로 MongoDB 서버의 큐 컬렉션으로 저장한다. 그런데 하나의 MySQL 명령이 TCP Layer에서 여러 패킷으로 분리되었다면, 해당 패킷을 Map<{SourceIp:SourcePort}, []byte>에 저장한다. 그리고 이후 캡쳐된 패킷이 동일 {SourceIp:SourcePort}에서 전송된 패킷인 경우 Map에 저장해둔 이전 패킷과 조합(Assembly)해서, MySQL payload 크기와 MySQL payload header에 설정된 data size를 비교하여 완전한 MySQL 명령이 조립되었는지 확인한다. 그리고 어느 시점에 완전한 MySQL 명령이 조립되면, MongoDB 서버의 큐 컬렉션으로 저장한다.

이런 패킷 조립과정에서 Map에 임시 저장되어 있던 패킷은 아래 조건이 되면 자동적으로 폐기 처리된다.
- Map에 저장되어 있던 패킷이 너무 오래된 경우 (`-packetexpire` 옵션에 설정된 값(second)보다 오래된 경우)
- 새롭게 캡쳐된 패킷이 이전 패킷과 조립 과정이 필요없는 경우 (새로운 패킷의 MySQL payload 크기와 MySQL payload header에 설정된 data size가 동일한 경우)

sendUserPacket() 함수에서는 수집된 MySQL 명령을 즉시 MongoDB의 큐 컬렉션으로 저장하는 것이 아니라, 아래와 같이 지정된 개수를 모아서 한번에 Batch로 MongoDB 큐 컬렉션에 저장하도록 되어 있다.
```go
// 패킷이 queueBatchSize 변수만큼 모이면, Queue(MongoDB)로 모아서 저장 
const queueBatchSize int = 30

...

collection := session.DB(dbName).C(getCollectionName(collectionPrefix, queueId))
bulk := collection.Bulk()
for _, v := range packets{
        bulk.Insert(bson.M{"ip": v.SrcIp, "port": v.SrcPort, "req": v.ParsedRequest})
}

var err error
_/*bulkRes*/, err = bulk.Run()
```

#### Issue
##### 1. Initial Database
MRTECollector는 서비스중인 MySQL 서버로 유입되는 트래픽을 캡쳐해서 MRTEPlayer로 전달하게 된다. 여기에서 한가지 문제점은 MRTECollector가 처음 실행되기 이전부터 연결되어 있던 컨넥션들의 상태 정보(세션 변수 값과 세션의 Default Database 정보)는 가져올 수 없다는 것이다. 뿐만 아니라 PacketDropped나 PacketIfDropped로 인해서 누락된 패킷이 발생하게 되면 Default Database 정보를 놓칠수도 있다. 그런데 이런 세션의 상태 정보가 제대로 설정되지 않으면 쿼리가 실패하게 되어 부하 전달이 무의미해질 수도 있기 때문에, 매우 중요하다. 그래서 MRTECollector는 20초 간격으로 Source MySQL 서버에 연결된 세션의 Default Database 정보를 일괄로 모아서 MongoDB 큐 컬렉션으로 전달하고 MRTEPlayer의 SQLPlayer들이 Default Database를 최신으로 갱신할 수 있도록 아래와 같이 보완(물론 대부분의 세션들은 문제없이 Default Database 정보를 가지고 있겠지만, 일부 세션들의 Initial Database 정보 설정을 위해서) 하고 있다.
```go
// 20초에 한번씩 MySQL 서버의 모든 연결에 대해서 현재 접속된 DB정보를 MongoDB Queue로 전송
if loopCounter % 20 == 0 {
    sendConnectionDefaultDatabase()
}

때로는 이렇게 전달되는 Default Database 정보(또는 MRTEPlayer가 자체적으로 Default Database를 찾는 로직)가 MRTEPlayer를 더 혼란스럽게 해서 SQLPlayer의 쿼리 실행을 더 방해할 수도 있다. 만약 이런 경우에 MRTEPlayer가 하나의 Database를 대상으로 쿼리를 실행해도 된다면, MRTEPlayer의 `--mysql_default_db` 옵션에 해당 Database를 설정하고 실행 도중에서는 Default Database를 변경하지 않도록 `--mysql_force_default_db` 옵션을 true로 설정하는 것이 도움이 될 수도 있다.
```

##### 2. SSL
MySQL 5.7 버전부터는 Open SSL을 사용하는 MySQL서버의 경우, 자동으로 SSL이 활성화되도록 구현되었다. 그래서 별도의 SSL 옵션을 활성화하지 않아도 MySQL 서버의 옵션을 살펴보면 아래와 같이 SSL이 활성화되어서 Client-server 간의 통신이 암호화되어 통신하는 것을 확인할수 있다.
```
mysql> show global variables like '%ssl%';
+---------------+-----------------+
| Variable_name | Value           |
+---------------+-----------------+
| have_openssl  | YES             |
| have_ssl      | YES             |
| ssl_ca        | ca.pem          |
| ssl_capath    |                 |
| ssl_cert      | server-cert.pem |
| ssl_cipher    |                 |
| ssl_crl       |                 |
| ssl_crlpath   |                 |
| ssl_key       | server-key.pem  |
+---------------+-----------------+
```

물론 그렇다 하더라도, JDBC를 포함해서 다른 Client Driver를 사용하는 경우 SSL을 명시적으로 활성화해야만 암호화 통신을 하게 된다. 아마도 대 부분 응용 프로그램들이 useSSL 옵션을 활성화하지 않기 때문에 별다른 문제가 되지는 않는다. 그런데 MySQL Client를 이용해서 MySQL 서버에 접속하는 경우에는 자동으로 SSL이 활성화된다. 그래서 MySQL Client Shell을 이용해서 MRTECollector와 MRTEPlayer를 테스트하는 경우에는 아래와 같이 SSL을 비활성화해서 로그인하거나 MySQL 서버의 SSL 기능을 비활성화해야 한다. **현재 버전의 MRTE는 SSL Connection을 지원하지 않음**
```
## MySQL Client가 서버 접속시 SSL을 비활성화
bash> mysql --skip-ssl -uuser -p
bash> mysql --ssl-mode=DISABLED -uuser -p

## 또는 MySQL Server 옵션 파일에 skip_ssl 옵션을 적용하여 MySQL 서버가 SSL을 지원하지 않도록 설정
mysql> show global variables like '%ssl%'; /* skip_ssl을 적용후, MySQL 서버 세션 & 글로벌 변수 확인 */
+---------------+----------+
| Variable_name | Value    |
+---------------+----------+
| have_openssl  | DISABLED |
| have_ssl      | DISABLED |
| ssl_ca        |          |
| ssl_capath    |          |
| ssl_cert      |          |
| ssl_cipher    |          |
| ssl_key       |          |
+---------------+----------+
```

##### 3. PreparedStatement
MRTECollector는 Source MySQL 서버로 유입되는 쿼리 요청 패킷을 수집해서 MRTEPlayer로 전달하는 방식으로 작동한다. 그런데 만약 서비스에서 Text Protocol이 아니라 Binary Protocol을 사용하게 되면(MySQL 서버에서 Server-side PreparedStatement 방식 사용) MRTECollector는 이 패킷을 캡쳐한다 하더라도 그냥 무시하게 된다. 이는 이미 연결된 세션들이 가지고 있는 PreparedStatement 정보를 MRTECollector가 MRTEPlayer로 전달할 수 있는 방법이 없기 때문에, 기존 연결된 세션들의 쿼리는 수집해도 재현할 수가 없기 때문이다.

그나마 다행인 것은, MySQL 서버에 접속하는 JDBC Application에서 PreparedStatement 객체를 사용한다 하더라도 실제 이것이 Server-side PreparedStatement를 사용하는 것을 의미하지는 않는다. 실제 MySQL JDBC Driver에서 Server-side PreparedStatement를 사용하기 위해서는 JDBC driver에서 `useServerPrepStmts=true` 옵션이 명시되어야 한다. 즉 JDBC Application에서 컨넥션을 생성할 때 `useServerPrepStmts=true` 옵션을 명시하지 않았다면 Application에서 PreparedStatement 객체를 사용한다 하더라도, 이는 Client side에서 PreparedStatement를 에뮬레이션하는 방식으로 작동하는 것이고 실제 MySQL 서버와의 통신은 Text Protocol(Adhoc Query)이 사용되고 있는 것으로 볼 수 있다.

### MongoDB Queue(Capped Collection)
MRTECollector의 `-threads` 옵션에 설정된 수만큼 MongoDB의 Capped Collection을 생성하게 된다. 즉 `--threads` 옵션에 설정된 수만큼 `sendUserPacket()` 쓰레드가 생성되고, 각 쓰레드는 자기에게 주어진 Capped Collection을 생성해서 각자의 컬렉션에만 챕쳐된 MySQL 명령을 저장하게 된다.

#### Capped Collection
`-mongodb` 옵션에 설정된 Database에 `-mongocollection`에 설정된 prefix를 이용해서 `-threads` 옵션에 설정된 개수만큼 컬렉션을 생성하게 된다. 이때 컬렉션의 이름은 아래와 같이 주어진 prefix에 '0'으로 패딩된 숫자 값을 붙혀서 컬렉션을 이름을 결정한다. (아래 예제는 `-threads` 옵션이 10으로 설정된 경우)
```json
> use mrte
switched to db mrte

> show tables;
mrte000
mrte001
mrte002
mrte003
mrte004
mrte005
mrte006
mrte007
mrte008
mrte009
```

MRTECollector가 시작되면, 우선 아래와 같이 먼저 컬렉션을 삭제하고 새롭게 생성하게 된다.
```go
for idx:=0; idx<*threads; idx++ {
    collectionName := fmt.Sprintf("%s%03d", prefix, idx)
    collection := session.DB(dbName).C(collectionName)

    // 우선 동일 이름의 컬렉션이 있다면 먼저 삭제
    collection.DropCollection()
        
    // 주어진 사이즈로 Capped Collection 생성
    collectionInfo := &mgo.CollectionInfo{
        Capped:   true,
        MaxBytes: *mongo_capped_size,
    }
        
    err := collection.Create(collectionInfo)
    if err != nil {
        fmt.Printf("%+v \n", err)
    }
}
```

Capped Collection은 MaxSize 옵션을 가지는데, 이 옵션 값은 `-mongocollectionsize`에 주어진 값을 이용해서 최대 크기를 설정하게 된다. 만약 별도로 `-mongocollectionsize` 옵션이 설정되지 않으면, 각 Capped Collection은 최대 50MB까지 저장할 수 있는 컬렉션으로 생성된다. MongoDB의 Capped Collection은 새로운 도큐먼트가 저장되면 컬렉션의 제일 끝에 Append하고, 계속된 INSERT로 컬렉션의 크기가 MaxBytes 옵션값보다 커지면 자동적으로 컬렉션의 제일 앞쪽부터 필요한 만큼 도큐먼트를 자동 삭제하게 된다.

만약 MRTEPlayer가 읽어가기도 전에 앞쪽의 도큐먼트가 용량 제한으로 인해서 삭제되면, MRTEPlayer의 CappedCollectionTailer는 도큐먼트 페치를 실패하게 된다. 만약 컬렉션의 개수가 적고 MRTECollector가 상당히 많은 패킷을 캡쳐하는 경우라면, Capped Collection의 사이즈를 크게 조정해서 MRTECollector를 시작하도록 하자.

#### Initial Marker
MongoDB의 Capped Collection은 최소 1건 이상의 도큐먼트를 가지고 있어야지만, CappedCollectionTailer가 다음 도큐먼트의 저장을 Waiting할 수있게 된다 (즉 Capped Collection에 도큐먼트가 한건도 없으면 CappedCollectionTailer의 Tailable Cursor 쿼리는 실패하게 된다). 그래서 MRTECollector는 MongoDB의 Capped Collection을 생성함과 동시에 아래와 같은 "Initial Marker" 도큐먼트를 저장한다. 하지만 이 도큐먼트는 Tailable Cursor가 작동하기 위한 용도이므로, MRTEPlayer는 이 레코드를 SQLPlayer로 전달하지 않고 그냥 Skip하게 된다.
```json
{
	"_id" : ObjectId("xxxxxxxxxxxxxxxxxxxxxxxx"),
	"ip" : "127.0.0.1",
	"port" : 0,
	"req" : {
		"c" : 127,
		"q" : "Initial Marker for CappedCollection"
	}
}
```

### Document Format
캡쳐된 MySQL 명령은 MongoDB에 저장될 때 아래와 같은 포맷의 도큐먼트로 저장된다.
```json
mongo> db.mrte000.find().sort({$natural:1}).limit(3).pretty()
{
	"_id" : ObjectId("5b63d864f950cb99a0c97367"),
	"ip" : "127.0.0.1",
	"port" : 0,
	"req" : {
		"c" : 127,
		"q" : "Initial Marker for CappedCollection"
	}
}
{
	"_id" : ObjectId("5b63d872f950cb99a0c973d1"),
	"ip" : "10.51.28.173",
	"port" : 40170,
	"req" : {
		"c" : 126,
		"q" : "8da2bf81000000400800000000000000000000000000000000000000000000006d7274653200144722f5610fe0d29d4adf47e40be67b086d0879d66d7973716c736c6170006d7973716c5f6e61746976655f70617373776f72640054035f6f73054c696e75780c5f636c69656e745f6e616d65086c69626d7973716c045f706964063136393138350f5f636c69656e745f76657273696f6e06352e372e3137095f706c6174666f726d067838365f3634"
	}
}
{
	"_id" : ObjectId("5b63d872f950cb99a0c973d2"),
	"ip" : "10.51.28.173",
	"port" : 40170,
	"req" : {
		"c" : 3,
		"q" : "select * from mysqlslap where id=123"
	}
}
```

MongoDB에 저장된 도큐먼트는 PK(_id) 필드와 "ip" 그리고 "port" 마지막으로 "req" 필드를 가지고, "req" 필드는 다시 서브 도큐먼트로 "c"필드와 "q"필드를 가진다.
- _id : MongoDB 컬렉션에 저장될 때 자동으로 증가하는 AutoIncrement 값 (CappedCollectionTailer의 연결이 끊어져서 다시 쿼리를 하게 되는 경우에는, 이 값을 이용해서 마지막 Replay했던 위치를 찾아서, 그 시점부터 Replay를 재개)
- ip : MySQL 명령을 요청한 Client 서버의 ip 주소
- port : MySQL 명령을 요청한 Client 서버의 port 번호, ip와 port를 이용해서 이 명령을 어느 SQLPlayer가 실행할지를 결정한다.
- req : MySQL 클라이언트가 요청한 명령과 쿼리
  - c : MySQL 클라이언트가 요청한 명령 (아래 표 참조)
  - q : MySQL 클라이언트가 요청한 명령의 데이터 (쿼리 또는 Database 명 등)

| Command       | c 필드 값       | q 필드 값  | 설명 |
|---------------|---------------|-----------|----|
| COM_QUIT      | 0x01          | EMPTY String |Client가 connection을 종료할 때 발생하는 MySQL 명령|
| COM_INIT_DB   | 0x02          | Database 이름 |Client에서 Default database를 이동할때 발생하는 MySQL 명령|
| COM_QUERY     | 0x03          | SQL Statement |Client에서 쿼리를 실행할 때 발생하는 MySQL 명령|
| COM_LOGIN     | 0x7e          | Connection options (hex string) |Client가 MySQL 서버에 로그인할 때 발생하는 MySQL 명령, 이 경우 상당히 많은 정보가 전달되는데 이를 모두 MRTECollector에서 분석하는 것이 아니어서 TCP 패킷에서 MySQL Payload를 통째로 MongoDB에 저장한다. 이때 저장되는 데이터는 byte array를 hex string으로 변환해서 저장횐다|
| COM_UNKNOWN   | 0x7f          | Dummy String |위에서 설명한 Initial Marker와 같은 MySQL 서버가 인지하지 못하는 명령을 위한 용도로 사용|

물론 이 이외에도 MySQL의 Client-Server protocol에는 수많은 명령들이 있지만, MRTE에서 나머지 명령들은 모두 사용하지 않는다. (현재 버전에서는)

### MRTEPlayer

#### Architecture
```
+-----------------------+----------------------------------------------------------+-------------------
|    Queue (MongoDB)    |                    MRTEPlayer                            |   Target MySQL
+-----------------------+----------------------------------------------------------+-------------------
                                                         / Queue -> SQLPlayer[...] -> DBConnection[...]
                                                         | Queue -> SQLPlayer[...] -> DBConnection[...]
                                                         | Queue -> SQLPlayer[...] -> DBConnection[...]
                                                         | Queue -> SQLPlayer[...] -> DBConnection[...]
                                                         | Queue -> SQLPlayer[...] -> DBConnection[...]
  MongoDB(mrte.mrte000) -> CappedCollectionTailer[0] ->  | Queue -> SQLPlayer[...] -> DBConnection[...]
  MongoDB(mrte.mrte001) -> CappedCollectionTailer[1] ->  | Queue -> SQLPlayer[...] -> DBConnection[...]
  MongoDB(mrte.mrte002) -> CappedCollectionTailer[2] ->  | Queue -> SQLPlayer[...] -> DBConnection[...]
  MongoDB(mrte.mrte003) -> CappedCollectionTailer[3] ->  | Queue -> SQLPlayer[...] -> DBConnection[...]
  MongoDB(mrte.mrte004) -> CappedCollectionTailer[4] ->  | Queue -> SQLPlayer[...] -> DBConnection[...]
                                                         | Queue -> SQLPlayer[...] -> DBConnection[...]
                                                         | Queue -> SQLPlayer[...] -> DBConnection[...]
                                                         | Queue -> SQLPlayer[...] -> DBConnection[...]
                                                         | Queue -> SQLPlayer[...] -> DBConnection[...]
                                                         ` Queue -> SQLPlayer[...] -> DBConnection[...]
+-----------------------+----------------------------------------------------------+-------------------
```

#### CappedCollectionTailer[...]
CappedCollectionTailer는 MongoDB 큐 컬렉션에 대해서 TailableCursor 객체를 생성하고, 해당 컬렉션에 도큐먼트가 INSERT될 때마다 해당 도큐먼트를 페치해서 SQLPlayer로 전달한다.

#### Queue
CappedCollectionTailer는 페치한 도큐먼트(MySQL 명령)을 SQLPlayer에게 전달하는 역할을 수행한다. CappedCollectionTailer와 SQLPlayer는 서로 무관하게 각자 독립된 쓰레드로 작동하는데, 이때 상호 간섭을 줄이기 위해서 Queue를 커뮤니케이션 채널로 활용한다. Queue는 최대 10000개까지 MySQL 명령을 저장할 수 있는데, 실제 Queue의 개수가 최대 5000개를 넘어서면 CappedCollectionTailer가 MongoDB 서버로부터 데이터를 페치하는 속도를 늦추도록 sleep을 하게 된다. 실제 SQLPlayer는 컨넥션의 개수만큼 만들어지고, SQLPlayer 쓰레드별로 Queue를 하나씩 가지기 때문에 5000개까지 실행되지 못한 MySQL 명령이 큐잉된다면 상당한 메모리를 사용할 수 있게 될 것이다. SESSION_QUEUE_SIZE_DELAY는 적절히 테스트하는 서비스의 특성에 맞게 적절히 조절해서 MRTEPlayer를 리빌드해서 사용하도록 하자.
```java
public final static int SESSION_QUEUE_SIZE_DELAY = 5000;
public final static int SESSION_QUEUE_SIZE_ABORT = 10000;
```

#### SQLPlayer
SQLPlayer는 패킷을 캡쳐하는 Source MySQL 서버에 생성된 컨넥션 개수만큼 SQLPlayer 쓰레드 그리고 동일한 개수의 컨넥션을 Target MySQL 서버로 연결하고, MRTECollector가 캡쳐한 MySQL 명령을 그대로 재현한다. 이때 SQLPlayer 쓰레드는 {SourceIp:SourcePort}를 유니크한 식별자로 사용한다. 즉 MRTECollector에서 패킷을 캡쳐해서 MongoDB 큐 컬렉션에 저장할 때, {SourceIp:?? , SourcePort:??} 값을 같이 전달하는데 MRTEPlayer는 이 값을 이용해서 해당 명령을 실행할 SQLPlayer를 찾고 해당 SQLPlayer로 명령을 전달한다.

SQLPlayer는 Target MySQL 서버에 처음 접속하면, 아래 쿼리를 이용해서 Target MySQL 서버에 생성되어 있는 모든 데이터베이스와 테이블 목록을 Map<Table, Database>으로 캐시한다. 그리고 SQLPlayer가 쿼리를 실행할 때 아래 에러가 발생하면, SQLPlayer는 에러 메시지의 테이블 명을 이용해서 Default Database를 자동 재설정할 것이다.
```sql
ERROR 1146 (42S02): Table 'mydb.mytable' doesn't exist
ERROR 1054 (42S22): Unknown column 'mytable.mycolumn' in 'field list'
```

하지만 이렇게 자동으로 Default Database를 설정한다 하더라도, 동일한 테이블 구조를 가진 데이터베이스가 여러 개인 경우에는 아무런 도움이 안될 것이다. 이런 경우에는 뾰족한 해결책이 없다. 만약 다행히 Database별로 MRTEPlayer를 별도로 기동하는 경우라면, `--mysql_force_default_db` 옵션을 `true`로 설정하여 SQLPlayer가 옵션으로 설정된 초기 데이터베이스(`--mysql_default_db`)를 항상 그대로 유지하도록 하는 것이 도움이 될 것이다.

