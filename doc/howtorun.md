### How to run MRTE

#### MRTE 실행 순서
1. MongoDB 시작
2. MRTECollector 시작
3. MRTEPlayer 시작

각 컴포넌트 재시작시
- MRTEPlayer만 재시작하는 경우, MRTEPlayer가 재시작되는 경우 마지막 실행했던 Event를 기억하지 못한다. 그래서 MRTEPlayer가 재시작되는 경우에는 MongoDB의 Capped collection에 저장된 모든 Event를 한번씩 더 실행하게 된다.
- MRTECollector만 재지작하는 경우, MRTECollector는 시작될 때 Capped Collection을 삭제하고 새로 생성하게 되는데 이 과정에서 MRTEPlayer의 Taiable Cursor의 데이터 읽기가 실패하게 된다.
- 가능하면 MRTECollector또는 MRTEPlayer만 재시작하는 작업은 하지 않도록 하자. 현재 구현에는 이런 경우에 대한 핸들링이 포함되어 있지 않기 때문이다.

#### MongoDB 시작
MongoDB 서버는 MRTECollector에서 수집된 MySQL 명령을 MRTEPlayer로 전달하는 매개 역할을 하게 된다. 그래서 MongoDB 서버가 저장되는 데이터를 영구적으로 보관(Durable)할 필요가 없다. 그래서 MRTE에서 사용되는 MongoDB는 아래와 같이 "Percona Server for MongoDB"를 이용해서 메모리 엔진으로 Wiredtiger를 기동하는 것이 좋다(물론 꼭 메모리 스토리지 엔진을 사용해야 하는 것은 아님).

```yml
processManagement:
    fork: true
    pidFilePath: /var/run/mongodb/mongod.pid

operationProfiling:
    mode: slowOp
    slowOpThresholdMs: 100

systemLog:
    quiet: true
    destination: file
    path: /data/mongodb/mongod.log
    logRotate: "rename"
    logAppend: false

net:
    port: 30000
    maxIncomingConnections: 500

storage:
    directoryPerDB: true
    dbPath: /data/mongodb
    journal:
        enabled: false

    engine: inMemory
    inMemory:
        engineConfig:
            inMemorySizeGB: 5
            statisticsLogDelaySecs: 0
```

#### MRTECollector 시작
MRTECollector는 GoLang으로 개발되어 있으며, 운영 체제 플랫폼에 따라서 새로 빌드를 해야 할수도 있다. 우선 MRTECollector는 아래오 같이 간단히 빌드를 할수 있다.

```shell
## pcap 라이브러리 설치 (miekg packet capture 라이브러리는 pcap 라이브러리를 사용)
yum install libpcap-devel

## Go 컴파일러가 /usr/local/go 디렉토리에 설치된 것을 가정
export PATH=$PATH:/usr/local/go/bin
## MRTECollector 소스 코드의 디렉토리는 /src/MRTE2/MRTECollector로 가정
export GOPATH=/src/MRTE2/MRTECollector

cd $GOPATH
go bulid MRTECollector.go
```

이제 MRTECollector를 시작하면 되는데, MRTECollector는 다음과 같은 옵션을 필요로 한다.
```
# ./MRTECollector --help
2018/08/06 15:05:13 MRTECollector - 2.0.0

Usage of ./MRTECollector:
  -fastparsing
        Filter-out no-application payload packet and use custom packet parser (default true)
  -interface string
        Interface to get packets from (default "eth0")
  -mongocollection string
        MongoDB collection name prefix (default "mrte")
  -mongocollectionsize int
        MongoDB capped-collection max-bytes (default 52428800)
  -mongodb string
        MongoDB DB name (default "mrte")
  -mongouri string
        MongoDB server connection uri (default "mongodb://127.0.0.1:27017?connect=direct")
  -mysqluri string
        MySQL server connection uri (default "userid:password@tcp(127.0.0.1:3306)/")
  -onlyselect
        relay only select statement (default true)
  -packetexpire int
        Packet expire timeout seconds (default 10)
  -port int
        Port to get packets from (default 3306)
  -snaplen int
        SnapLen for pcap packet capture (default 65536)
  -threads int
        Assembler threads (default 5)
  -verbose
        Log whenever we see a packet
```

- `-fastparsing` : fastparsing=true로 설정되면, 캡쳐된 packet의 크기가 0x42(66) 바이트 이하(즉 Application payload가 없으면)이면, 패킷을 분석하지 않고 버린다. 그리고 fastparsing=true이면, gopacket library를 사용하지 않고 miekg packet capture 라이브러리를 사용한다. 코드를 정확히 이해하지 못했다면, fastparsing 옵션은 Default 값을 유지하도록 하자.
- `-interface` : Packet을 캡쳐할 interface 이름 (일반적으로 "eth0" 이므로 Default 값을 유지토록 하자)
- `-mongouri` : MongoDB 서버의 접속 URL (GoLang의 MongoDB Driver에서 지원하는 포맷의 URI)
- `-mongodb` : 캡쳐된 패킷이 저장되는 컬렉션의 Database 명
- `-mongocollection` : 캡쳐된 패킷을 저장할 MongoDB의 Capped Collection 명(컬렉션의 Prefix 이름)
- `-mongocollectionsize` : Capped Collection의 최대 크기 (Byte 단위로 입력)
- `-onlyselect` : 캡쳐된 패킷에서 SELECT 명령(SET과 SHOW 명령 포함)만 MRTEPlayer로 전달
- `-packetexpire` : MRTECollector에서 Packet의 조립 과정에서 오래된 패킷은 버리는데, 이때 packetexpire 옵션에 설정된 값(초)보다 크면 오래된 패킷으로 간주하고 버린다.
- `-port` : MRTECollector가 패킷을 캡쳐할 Port 설정 (MRTECollector에서는 "tcp dst port $PORT"로 BPF Filter를 설정)
- `-threads` : MRTECollector 내부적으로 패킷을 분석할 쓰레드 개수를 설정하며, 이 threads 옵션의 개수에 따라서 Capped Collection의 개수도 결정된다. 또한 MRTEPlayer에서 --mongo_collections 옵션도 이 수치와 동일값으로 설정되어야 한다.
- `-verbose` : MRTECollector가 수집한 패킷의 Hexdump까지 상세한 내용을 출력한다. 소규모 트래픽이 유입되는 환경에서 디버깅 용도로만 사용하도록 하자.

만약 설정이 복잡하다면, 아래 옵션으로 시작해볼 것을 권장한다.
```bash
./MRTECollector \
  -mongouri='mongodb://mongodb-mrte-queuedb:30000?connect=direct' \
  -mongodb="mrte" \
  -mongocollection="mrte" \
  -threads=11 \
  -fastparsing=true \
  -mysqluri='mrte2:mrte2@tcp(127.0.0.1:3306)/'
```

**MRTECollector는 내부적으로 pcap library를 사용하기 때문에, 반드시 패킷을 캡쳐하고자 하는 MySQL 서버가 기동중인 서버에서 실행해야 함**

**MRTECollector는 자신 프로세스가 사용중인 메모리(/proc/self/statm에서 residentMemory 크기)가 512MB를 넘어서면 자동으로 Terminiate된다. 만약 512MB 메모리가 너무 크다면, MRTECollector/MRTECollector.go 에서 checkMemoryUsage(512MB)를 적당히 줄여서 실행**

#### MRTEPlayer 시작
MRTEPlayer는 Java로 개발되었으며, 플랫폼에 따라서 빌드를 새로할 필요는 없다. MRTEPlayer는 아래와 같은 옵션들을 필요로 한다.
```
--mysql_url              : MySQL target database jdbc url
--mysql_init_connections : MySQL initial connections to prepare before replay collected statement
--mysql_default_db       : MySQL default database
--mysql_force_default_db : Force MySQL default database
--database_remap         : Database remapping
--max_packet_size        : Maximum mysql packet size
--mongo_url              : MongoDB(queue) connection url
--mongo_db               : MongoDB(queue) database name
--mongo_collectionprefix : MongoDB(queue) collection name prefix
--mongo_collections      : MongoDB(queue) collections partitioned
--slow_query_time        : MySQL slow query time in milli seconds
--verbose                : Verbose logging
```

- `--mysql_url` : 캡쳐된 패킷을 재실행(Replay)할 MySQL 서버의 접속 URL (JDBC URL)
- `--mysql_init_connections` : 일부 MySQL 서버는 몇 천개씩 컨넥션을 가진 경우도 있는데, 이런 경우 MRTEPlayer가 처음에 오픈해야 할 컨넥션이 너무 많아서 초기 처리 성능이 떨어질 수 있다. 이를 위해서 MRTEPlayer가 미리 컨넥션을 준비해두도록 할 수 있는데, 이때 MRTEPlayer가 미리 생성해둘 컨넥션의 개수를 설정한다.
- `--mysql_default_db` : MRTEPlayer의 SQLPlayer가 Target MySQL 서버의 컨넥션을 생성한 후, Default로 이동할 DB의 이름
- `--mysql_force_default_db` : MRTEPlayer의 SQLPlayer는 MRTECollector가 캡쳐해서 내려주는 명령에 따라서 계속 DB를 이동하게 될 수도 있다. 그런데, MRTECollector가 모든 컨넥션의 Default DB를 100% 동기화할 수는 없다. 그래서 때로는 MRTEPlayer의 SQLPlayer가 쿼리를 실행하는 도중 "테이블이 없다"거나 "필드가 없다"라는 등의 에러를 발생시킬 수도 있는데, 이때에는 자동으로 해당 테이블을 가진 DB를 Default DB로 조정하기도 한다. 하지만 이런 보완책이 100% 해결책이 될수는 없다. 만약 MRTEPlayer의 SQLPlayer가 (MRTECollector가 캡쳐하는 Default DB 명령과 무관하게) 특정 DB에서만 쿼리를 실행하도록 하고자 한다면, mysql_force_default_db 옵션을 true로 설정하도록 하자.
- `--database_rema`p : Source MySQL과 Target MySQL의 DB 이름이 다른 경우, database_remap 옵션을 활용하도록 하자. 
- `--max_packet_size` : 캡쳐된 MySQL 명령의 최대 크기를 제한한다. (MRTE2에서는 불필요한 옵션임 - 사용 안함)
- `--mongo_url` : MRTEPlayer가 캡쳐된 MySQL 명령을 읽어올 MongoDB 서버의 접속 정보
- `--mongo_db` : MRTEPlayer가 캡쳐된 MySQL 명령을 읽어올 MongoDB 서버의 Database 명
- `--mongo_collectionprefix` : 캡쳐된 MySQL 명령을 읽어올 컬렉션 이름 (컬렉션 명의 Prefix)
- `--mongo_collections` : 캡쳐된 MySQL 명령을 읽어올 컬렉션의 개수 (`--mongo_collectionprefix`와 `--mongo_collections` 옵션을 이용해서 최종 컬렉션 이름 결정)
- `--slow_query_time` : SQLPlayer에서 실행하는 쿼리들중에서 `--slow_query_time` 보다 오래 걸리는 쿼리는 Slow query로 마킹하고 통계 정보에 포함해서 출력
- `--verbose` : 디버깅을 위해서 조금 더 자세한 내용들을 로그 파일로 출력한다.

만약 설정이 복잡하다면, 아래 옵션으로 시작해볼 것을 권장한다. 혹시 Java의 GC에 대해서 익숙하다면, CMS 또는 CMS 옵션은 별도로 조정해도 무방할 것으로 보인다.
```bash
CLASSPATH=.:./build/
for jar in ./lib/*.jar; do
    CLASSPATH=$CLASSPATH:$jar
done

java \
-XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:NewSize=1024M -XX:SurvivorRatio=3 -XX:MaxTenuringThreshold=3 \
-XX:+CMSParallelRemarkEnabled -XX:CMSInitiatingOccupancyFraction=70 -XX:+UseCMSInitiatingOccupancyOnly \
-XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintTenuringDistribution -XX:+PrintGCApplicationStoppedTime \
-Xloggc:mrte_player_gc.log -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=20M \
-Xmx2G -Xms2G -cp $CLASSPATH mrte.MRTEPlayer \
--mysql_url='jdbc:mysql://127.0.0.1:3306/mysqlslap?user=mrte2&password=mrte2' \
  --mysql_init_connections=500 \
  --mysql_default_db="mysqlslap" \
  --mongo_url='mongodb://mongodb-mrte-queuedb:30000/mrte?connectTimeoutMS=300000&authSource=admin' \
  --mongo_db="mrte" \
  --mongo_collectionprefix="mrte" \
  --mongo_collections=11 \
  --slow_query_time=100 \
  --verbose=true 2> error.log
```

