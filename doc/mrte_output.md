### Understanding MRTE output
MRTECollector와 MRTEPlayer는 각자 처리한 내용에 대한 메트릭들이나 상태 정보들을 초 단위로 출력하도록 하고 있는데, 이 정보를 활용하면 MRTECollector에서 캡쳐된 패킷과 처리 내용 그리고 MRTEPlayer가 Replay한 내용등을 확인할 수 있다. 또한 이 정보들을 이용하면 MRTECollector와 MRTEPlayer에서 발생중인 병목을 확인할 수도 있을 것이다.

#### MRTECollector output
MRTECollector가 실행되면, 아래와 같은 포맷의 상태 값들을 초당 1 라인씩 출력하게 된다.
```
DateTime                TotalPacket     ValidPacket    PacketDropped  PacketIfDropped  AssembledPackets  ExpiredPackets  WaitingQueueCnt         MQError
2018-08-01 14:31:37           55301           55301            83415                0                 0               0                0               0
2018-08-01 14:31:38           55693           55693            83419                0                 0               0                0               0
2018-08-01 14:31:39           56066           56062            82028                0                 0               0                0               0
2018-08-01 14:31:40           55161           55165            83249                0                 0               0                0               0
2018-08-01 14:31:41           55406           55405            81648                0                 0               0                0               0
```

- `TotalPacket` : 
   MRTECollector가 수집한 전체 패킷의 개수 (초당), 실제 현재 서버로 유입된 전체 패킷의 개수는 `TotalPacket` + `PacketDropped` + `PacketIfDropped`이며, `TotalPacket`은 pcap 라이브러리를 통해서 정상적으로 캡쳐된 패킷의 개수만을 의미한다.
- `ValidPacket` : 
   MRTECollector가 정상적으로 캡쳐한 패킷(`TotalPacket`)중에서 MRTECollector가 패킷 분석에 성공해서 MySQL payload를 분리해낸 패킷의 개수(초당)를 의미한다.
- `PacketDropped` : 
   MRTECollector에서는 빠른 패킷 분석을 위해서, 패킷을 캡쳐하는 쓰레드와 그 패킷을 분석하는 N개의 쓰레드로 구분되어 있다. 그럼에도 불구하고 하나의 쓰레드가 적절히 유입되는 패킷의 속도를 따라가지 못할 수도 있다. 이런 경우에는 MRTECollector 내부의 pcap 라이브러리가 처리되지 못한 패킷들을 버리게 되는데, 이때 버려진 패킷의 개수(초당)를 의미한다. 
- `PacketIfDropped` : 
   작성중
- `AssembledPackets` : 
   MySQL 서버로 전송되는 SQL 명령은 크기에 따라서 여러개의 TCP 패킷으로 분리되어서 서버로 전송되기도 한다. 이때 MRTECollector가 수집한 TCP 패킷이 하나의 온전한 MySQL 명령이 아니어서 앞 뒤로 전송된 패킷과 조립(Assembly)한 회수를 의미한다.
- `ExpiredPackets` : 
   때로는 `PacketDropped` 또는 `PacketIfDropped`의 증가 그리고 MRTECollector의 버그로 인해서 패킷이 정상적으로 조립되지 못하고 MRTECollector의 버퍼에 장시간 남아있을 수도 있는데, 이때 MRTECollector는 `-packetexpire`(Default 10초)에 설정된 시간 이상 버퍼에 남아있는 패킷은 버리게 되는데, 초당 버린 패킷의 수를 의미한다.
- `WaitingQueueCnt` : 
   pcap 라이브러리를 이용해서 패킷을 캡쳐하는 로직은 단일 쓰레드이며, 캡쳐된 패킷을 분석하는 쓰레드는 `-threads` 옵션에 지정된 개수만큼 생성된다. 이때 두 쓰레드는 GoLang의 Channel을 이용해서 패킷을 주고 받는데, 이때 패킷을 분석하는 쓰레드가 부족하거나 처리 성능이 떨어지면 Channel에 남아있는 패킷의 개수가 증가하게 된다. WaitingQueueCnt 필드에는 각 Channel에 남아있는 패킷의 전체 합을 의미한다.
- `MQError`
   분석된 패킷은 MongoDB로 저장되는데, 이때 MongoDB 저장이 실패한 회수(초당)을 의미한다.

위의 예제를 살펴보면, 대략 초당 13만~14만개정도의 패킷이 유입되고 있으며, 그중에서 8만여개의 패킷은 캡쳐되지 못하고 버려지고 있다는 것을 알 수 있다(즉, 알수 없는 이유로 인해서 MRTECollector 내부적인 병목이 있음을 알 수 있다). 그리고 `TotalPacket`과 `ValidPacket`의 차이가 적은 것을 보면, 캡쳐된 패킷은 모두 정상적으로 파싱되어서 MongoDB 큐 컬렉션을 저장된 것을 확인할 수 있다.

그리고 AssembledPackets 값이 0인 것을 보면, MySQL 서버의 SQL 명령이 대부분 하나의 Packet으로 전달될만큼 크기가 작다는 것과 잘못된 패킷 캡쳐나 분석으로 인해서 Expired된 패킷이 발생하지 않고 있다는 것을 알 수 있다. 또한 MRTECollector 내부적인 Channel에 적체된 패킷이 거의 없으며 MongoDB 큐 컬렉션 저장도 특별히 에러가 없음을 확인할 수 있다.

#### MRTEPlayer output
MRTEPlayer가 실행되면, 아래와 같은 포맷의 상태 값들을 초당 1 라인씩 출력하게 된다.
```
DateTime             TotalPacket  ErrorPacket  NewSession  ExitSession  UserRequest(Slow)  Error (NoInitDB Duplicated Deadlock LockTimeout)
2018-08-01 13:25:51        54950            0           0            0        54894(   0)      0 (       0          0        0           0)
2018-08-01 13:25:52        54867            0           0            0        54878(   0)      0 (       0          0        0           0)
2018-08-01 13:25:53        55192            0           0            0        55181(   0)      0 (       0          0        0           0)
2018-08-01 13:25:54        54785            0           0            0        54773(   0)      0 (       0          0        0           0)
2018-08-01 13:25:55        55322            0           0            0        55379(   0)      0 (       0          0        0           0)
```

- `TotalPacket` : 
   MRTEPlayer가 MongoDB 큐 컬렉션으로부터 가져온 MySQL 요청의 개수(초당)를 보여준다.
- `ErrorPacket` : 
   무시
- `NewSession` : 
   Target MySQL 서버로 생성된 새로운 연결의 개수(초당)를 보여준다. (이 경우는 SQLPlayer 쓰레드가 새롭게 생성되는 경우임)
- `ExitSession` : 
   Target MySQL 서버 연결이 종료된 개수(초당)를 보여준다. (이 경우는 SQLPlayer 쓰레드가 종료되는 경우임)
- `UserRequest` : 
   Target MySQL 서버로 쿼리를 실행한 회수(초당)를 보여준다.
  - `UserRequest(Slow)` : 
     Target MySQL 서버로 실행된 쿼리중에서 지정된 시간 이상 소요된 쿼리의 회수(초당)를 보여준다.
- `Error` : 
   Target MySQL 서버로 실행된 쿼리중에서 실패(SQL Error)한 경우의 회수(초당)를 보여준다. 여기에 보여지는 값은 아래 4가지 에러 + a임
  - `Error(NoInitDB)` : 
     Target MySQL 서버 컨넥션이 초기 DB가 설정되지 않아서 발생한 에러의 회수(초당)
  - `Error(Duplicated)` : 
     INSERT 명령이나 UPDATE 명령이 Duplicate Key Error가 발생한 회수(초당)
  - `Error(Deadlock)` : 
     Deadlock으로 인해서 쿼리가 실패한 회수(초당)
  - `Error(LockTimeout)` : 
     다른 세션과의 잠금 경합으로 인해서, Lock wait timeout으로 쿼리가 실패한 회수(초당)
     


**MRTECollector와 MRTEPlayer의 출력 내용에서 반드시 한 라인의 수치의 합계가 TotalXXXX와 정확히 맞아떨어지지 않을 가능성이 높다. MRTECollector나 Player에서는 그냥 초단위로 각 메트릭을 수집해서 보여주는데, 하나의 처리가 1초 이상 걸리거나 초단위가 바뀌는 시점에 걸쳐서 처리되는 경우들이 있으면 메트릭의 합계가 달라질 수 있기 때문이다.**

