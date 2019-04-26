package main

import (
	"fmt"
	"syscall"
	"strconv"
	"encoding/binary"
	//"encoding/hex"
	"log"
	"time"
	"flag"
	"strings"
	"io/ioutil"
	"github.com/google/gopacket"
	"github.com/google/gopacket/layers"
	//"github.com/google/gopacket/pcap"
	
	"github.com/miekg/pcap"
	
	"mrte"
)

// ------------------
// Version
const PROGNAME string = "MRTECollector"
const VERSION string = "2.0.0"

// MysqlPacket 채널의 버퍼링 개수
const channelBufferSize int = 1000
// 패킷이 queueBatchSize 변수만큼 모이면, Queue(MongoDB)로 모아서 저장 
const queueBatchSize int = 30

type MysqlPacket struct {
	IsParsed  bool
	RawData   []byte
	Request   *mrte.MysqlRequest	
}

// ------------------
// Application Parameters
var iface = flag.String("interface", "eth0", "Interface to get packets from")
var snaplen = flag.Int("snaplen", 65536, "SnapLen for pcap packet capture")
var port = flag.Int("port", 3306, "Port to get packets from")
// var filter = flag.String("filter", "tcp dst port 3306", "BPF filter for pcap")
var onlySelect = flag.Bool("onlyselect", true, "relay only select statement")
var packetExpireTimeoutSeconds = flag.Int("packetexpire", 10, "Packet expire timeout seconds")

var fastPacketParsing = flag.Bool("fastparsing", true, "Filter-out no-application payload packet and use custom packet parser")

var threads = flag.Int("threads", 5, "Assembler threads")

var mongo_uri = flag.String("mongouri", "mongodb://127.0.0.1:27017?connect=direct", "MongoDB server connection uri")
var mongo_db = flag.String("mongodb", "mrte", "MongoDB DB name")
var mongo_collection = flag.String("mongocollection", "mrte", "MongoDB collection name prefix")
// mongo_collections = threads
//var mongo_collections = flag.Int("mongocollections", 5, "MongoDB collection partition count")
var mongo_capped_size = flag.Int("mongocollectionsize", 50*1024*1024, "MongoDB capped-collection max-bytes")

// MySQL Server connection url for select default database of all connection
var mysql_uri = flag.String("mysqluri", "userid:password@tcp(127.0.0.1:3306)/", "MySQL server connection uri")

var logAllPackets = flag.Bool("verbose", false, "Log whenever we see a packet")

// ------------------
// Global variables
var linkType int
var queueTimeoutSeconds time.Duration
// var mysqlRequestChannels []chan mrte.MysqlRequest
var mysqlPacketChannels []chan *MysqlPacket
// var tempQueue chan mrte.MysqlRequest  ==> This is for test
var mysqlRequestMaps []map[string]mrte.MysqlRequest

// Status variables and interval to print status
const STATUS_INTERVAL_SECOND = uint64(1) // 1 second
var totalPackets           uint64
var assembledPackets       []uint64
var expiredPackets         []uint64
var validPackets           []uint64
var mqErrorCounters        []uint64

/**
 * THREAD
 * 테스트용 쓰레드 함수 (Queue에서 조립된 패킷을 가져와서 디버깅 용도로 화면 출력)
 */
/*
func dequeueUserPacket(){ //  ==> This is for test
	ticker := time.Tick(queueTimeoutSeconds)
	for {
		select {
			case packet := <-tempQueue:
                                log.Printf("MySQL Packet(%v:%v)\n", packet.SrcIp, packet.SrcPort)
				fmt.Printf("%s", hex.Dump(packet.Data))
			case <-ticker:
				// do nothing
				// log.Println("Queue waiter queueTimeoutSeconds")
		}
	}
}
*/

/**
 * THREAD 
 * threads 옵션으로 주어진 개수만큼 쓰레드를 생성해서 sendUserPacket 함수 실행
 * 각 세션별로 수집된 패킷이 재조합(Assembly)이 필요한지 판단해서 Queue로 전달
 */
func sendUserPacket(workerIdx int){
	forceFlush := false
	batched := 0
	batches := make([]*mrte.MysqlRequest, queueBatchSize)
	var mp *mrte.MysqlRequest
	var err error
	
	// -- For gopacket parser -----------------------------------------------
	// We use a DecodingLayerParser here instead of a simpler PacketSource.
	// This approach should be measurably faster, but is also more rigid.
	// PacketSource will handle any known type of packet safely and easily,
	// but DecodingLayerParser will only handle those packet types we
	// specifically pass in.  This trade-off can be quite useful, though, in
	// high-throughput situations.
	var eth layers.Ethernet
	var dot1q layers.Dot1Q
	var ip4 layers.IPv4
	var ip6 layers.IPv6
	var ip6extensions layers.IPv6ExtensionSkipper
	var tcp layers.TCP
	var payload gopacket.Payload
	
	parser := gopacket.NewDecodingLayerParser(layers.LayerTypeEthernet, &eth, &dot1q, &ip4, &ip6, &ip6extensions, &tcp, &payload)
	decoded := make([]gopacket.LayerType, 0, 4)
	// -- For gopacket parser -----------------------------------------------
	
	// 수집된 패킷을 버퍼링해서 배치로 Queue(MongoDB)로 전달하는데,
	// 패킷이 수집 빈도가 뜸한 경우에는 5밀리초 단위로 Queue로 전달하기 위해서 ticker 활용
	ticker := time.Tick(time.Millisecond * 5)
	for {
		select {
			case _packet := <-mysqlPacketChannels[workerIdx]:
				if _packet.IsParsed {
					// 이미 분석된 패킷(이 프로그램에서 인위적으로 생성된 패킷)인 경우, 바로 MongoDB 큐로 전송
					if _packet.Request!=nil {
						batches[batched] = _packet.Request
						batched++
					}
					break
				}else{
					if *fastPacketParsing {
						mp, err = mrte.ParsePacketFast(_packet.RawData, linkType)
						if err!=nil {
							break
						}
					}else{
						err = parser.DecodeLayers(_packet.RawData, &decoded)
						if err != nil {
							log.Printf("Error decoding packet: %v", err)
							break
						}
						mp, err = mrte.ParsePacketSlow(decoded, &ip4, &ip6, &tcp)
						if err!=nil {
							break
						}
						if mp==nil {
							break // Application layer payload가 비어있는 패킷인 경우
						}
					}
				}
				
				// 사용자 데이터를 가지지 않은 TCP Control Packet은 무시				
				if mp.Data==nil || len(strings.TrimSpace(string(mp.Data)))==0 {
					break
				}
				
				key := mp.SrcIp + ":" + strconv.Itoa(int(mp.SrcPort))
				p, ok := mysqlRequestMaps[workerIdx][key]

				if ok {
					// 만약 기존 mysqlRequestMaps에 저장되어 있던 패킷이 완성 상태로 수집되지 않은 
					// 너무 오랜 시간 방치되어 있었다면, 해당 패킷은 삭제한다. 
					// (일반적으로 하나의 명령이 여러 패킷으로 구분된 경우에는, 아주 짧은 시간내에 모두 연속적으로 전달되기 때문에, 이런 경우는 Packet이 누락되었을 가능성이 더 높다.)
					elapsed := time.Since(p.CapturedAt)
					if int(elapsed.Seconds()) > *packetExpireTimeoutSeconds {
						delete(mysqlRequestMaps[workerIdx], key) // 패킷 삭제
						expiredPackets[workerIdx]++
						ok = false
					}

					// 기존 mysqlRequestMaps에 남아있는 패킷이 있었다 하더라도, 
					// 현재 수신된 패킷이 하나의 완전한 MySQL 패킷이라면 기존 패킷과 조립(Assemble)하지 않고, 그대로 Queue로 전달한다.
					payloadSize := int(4 + (0xFFFFFF & binary.LittleEndian.Uint32(mp.Data)))
					if len(p.Data) == payloadSize {
						delete(mysqlRequestMaps[workerIdx], key) // 이 경우에도, 기존 Map에 있던 패킷은 삭제
						expiredPackets[workerIdx]++
						ok = false
					}
				}

				if ok { // mysqlRequestMaps에 아직 Queue로 전송되지 못한 partial-packet이 남아있다면,
					p.Data = append(p.Data, mp.Data...) // 기존 패킷과 새로운 패킷을 병함(Assemble)
					payloadSize := int(4 + (0xFFFFFF & binary.LittleEndian.Uint32(p.Data)))
					if len(p.Data) >= payloadSize { // 전체 데이터 크기가, MySQL packet header 크기보다 크면, Queue로 전송
						parsedDoc, err := mrte.ParsePacket(p.Data, *onlySelect)
						if err==nil {
							validPackets[workerIdx]++
							p.ParsedRequest = &parsedDoc
							batches[batched] = &p
							batched++
							// tempQueue <-p  //  ==> 디버깅을 위한 테스트 코드

							if *logAllPackets {
								log.Printf("send assembled request. Request-source(%v:%v), Request-size(%v), MySQL-payload-head-size(%v)\n", p.SrcIp, p.SrcPort, len(p.Data), payloadSize)
							}
						}else{
							if *logAllPackets {
								log.Printf("ignore assembled request(%v). Request-source(%v:%v), Request-size(%v), MySQL-payload-head-size(%v)\n", err, p.SrcIp, p.SrcPort, len(p.Data), payloadSize)
							}
						}
						delete(mysqlRequestMaps[workerIdx], key) // Remove key & value from map
					}else{
						assembledPackets[workerIdx]++
						if *logAllPackets {
							log.Printf("stack request to map(1). Request-source(%v:%v), Request-size(%v), MySQL-payload-head-size(%v)\n", p.SrcIp, p.SrcPort, len(p.Data), payloadSize)
						}
					}
				}else{ // mysqlRequestMaps에 아직 Queue로 전송되지 못한 partial-packet이 없었다면,
					payloadSize := int(4 + (0xFFFFFF & binary.LittleEndian.Uint32(mp.Data)))
					if len(mp.Data) >= payloadSize { // 전체 데이터 크기가, MySQL packet header 크기보다 크면, Queue로 전송
						parsedDoc, err := mrte.ParsePacket(mp.Data, *onlySelect)
						if err==nil {
							validPackets[workerIdx]++
							mp.ParsedRequest = &parsedDoc
							batches[batched] = mp
							batched++
							// tempQueue <-mp  // ==> 디버깅을 위한 테스트 코드

							if *logAllPackets {
								log.Printf("send request directly. Request-source(%v:%v), Request-size(%v), MySQL-payload-head-size(%v)\n", mp.SrcIp, mp.SrcPort, len(mp.Data), payloadSize)
							}
						}else{
							if *logAllPackets {
								log.Printf("ignore request directly(%v). Request-source(%v:%v), Request-size(%v), MySQL-payload-head-size(%v)\n", err, mp.SrcIp, mp.SrcPort, len(mp.Data), payloadSize)
							}
						}
					}else{
						assembledPackets[workerIdx]++
						mysqlRequestMaps[workerIdx][key] = *mp
						if *logAllPackets {
							log.Printf("stack request to map(2). Request-source(%v:%v), Request-size(%v), MySQL-payload-head-size(%v)\n", mp.SrcIp, mp.SrcPort, len(mp.Data), payloadSize)
						}
					}
				}

			case <-ticker:
				forceFlush = true // 수집된 패킷을 강제로 Queue로 전송하도록 플래그 설정
		}
		
		if batched>=queueBatchSize || forceFlush {
			if batched>0 {
				// inserted := mrte.InsertPackets(*mongo_db, *mongo_collection, workerIdx, batches[0:batched])
				inserted := mrte.InsertBulkPackets(*mongo_db, *mongo_collection, workerIdx, batches[0:batched])
				mqErrorCounters[workerIdx] += uint64(batched - inserted)
				for idx:=0; idx<20; idx++ {
					batches[idx] = nil
				}
			}

			batched = 0
			forceFlush = false
		}
	}
}

/**
 * 현재 MySQL 서버에 접속된 모든 Connection들의 Default Database 정보를 MongoDB 큐로 저장
 * 만약 이 정보가 전달되지 않으면, MRTECollector가 기동되기 전부터 연결되어 있던 컨넥션들은 DefaultDatabase가 어디인지 식별이 불가능하다.
 * 그로 인해서, MRTEPlayer에서 쿼리가 실패(DB가 초기화되지 않았다거나 테이블이 없다는 등등의 에러)하게 된다.
 */
func sendConnectionDefaultDatabase() {
	sessions := mrte.GetSessionDefaultDatabase(*mysql_uri)
	for _, session := range sessions {
		if session.SrcPort<0 { // 양수로 변환
			session.SrcPort *= -1
		}
		
		// workerIdx는 0 ~ (threads-1) 이내의 숫자 값
		workerIdx := int(session.SrcPort) % (*threads)

		mp := mrte.MysqlRequest{AlreadyParsed:true, SrcIp: session.SrcIp, SrcPort: session.SrcPort, ParsedRequest: session.ParsedRequest, CapturedAt: time.Now()}
		// fmt.Printf(">> sendConnectionDefaultDatabase : %v\n", mp)

		_packet := &MysqlPacket{IsParsed:true, RawData:nil, Request:&mp}
		mysqlPacketChannels[workerIdx] <- _packet
	}
}


/**
 * mysqlRequestMaps의 모든 엔트리를 검사하면서, 
 * Queue로 전송되지 못하고 오래된 미완성 패킷(partial packet)이 남아있는 경우 삭제
 */  
func flushExpiredRequest(workerIdx int){
	for k,p := range mysqlRequestMaps[workerIdx] {
		// Map에 저장된 패킷이 너무 오래된 것이라면, 삭제 
		elapsed := time.Since(p.CapturedAt)
		if int(elapsed.Seconds()) > *packetExpireTimeoutSeconds {
			delete(mysqlRequestMaps[workerIdx], k) // 저장된 패킷 삭제
		}
	}
}

/**
 * 수집된 패킷을 Go Channel을 통해서 sendUserPacket THREAD로 전달
 * sendUserPacket THREAD는 수집된 패킷을 전달받아서, 기존에 저장된 패킷(if exist)과 병합(Assemble)하여 외부 큐(MongoDB)로 전달한다.
 */
func assembleUserPacket(data []byte) {
	srcPortNo := mrte.GetPortNo(data, linkType)

	// data 메모리 공간은 다른 쓰레드에 의해서 재사용될 수 있기 때문에, 먼저 data 공간에 있는 데이터는 새로운 메모리 공간으로 복사
// =================================================================================
// gopacket library는 PacketDropped가 많이 발생해서, miekg library를 이용해서 패킷 캡쳐하도록 우회
// =================================================================================
//	copiedPacketData := make([]byte, len(data))
//	copy(copiedPacketData, data)
// =================================================================================
// miekg library에서는 이미 복사된 메모리 공간에 캡쳐된 패킷을 저장하기 때문에, 별도의 복사 작업 불필요
// =================================================================================

	// workerIdx는 0 ~ (threads-1) 이내의 숫자 값
	workerIdx := int(srcPortNo) % (*threads)
	_packet := &MysqlPacket{IsParsed:false, RawData:data, Request:nil}
	mysqlPacketChannels[workerIdx] <- _packet
}

/**
 * 패키 수집 및 처리에 대한 내역을 초단위로 출력
 */
func printProcessStatus(handle *pcap.Pcap){
	// Print processing informations
	loopCounter := 0
	
	cPacketDropped     := uint64(0)
	cPacketIfDropped   := uint64(0)

	cTotalPackets     := uint64(0)
	cAssembledPackets := uint64(0)
	cExpiredPackets   := uint64(0)
	cValidPackets     := uint64(0)
	cMqueueErrors     := uint64(0)
	cWaitQueuePackets := uint64(0)

	pPacketDropped     := uint64(0)
	pPacketIfDropped   := uint64(0)
		
	pTotalPackets     := uint64(0)
	pAssembledPackets := uint64(0)
	pExpiredPackets   := uint64(0)
	pValidPackets     := uint64(0)
	pMqueueErrors     := uint64(0)

	for {
		startTime := time.Now()
		if loopCounter % 20 == 0 {
			fmt.Println()
			fmt.Printf("DateTime                TotalPacket     ValidPacket    PacketDropped  PacketIfDropped  AssembledPackets  ExpiredPackets  WaitingQueueCnt         MQError\n")
			loopCounter = 0
		}

//		pcapStats, err := handle.Stats()
//		if err==nil {
//			//pcapStats.PacketsReceived
//			cPacketDropped = uint64(pcapStats.PacketsDropped)
//			cPacketIfDropped = uint64(pcapStats.PacketsIfDropped)
//		}
                        pcapStats, err := handle.Getstats()
                        if err==nil {
                                cPacketDropped = uint64(pcapStats.PacketsDropped)
                                cPacketIfDropped = uint64(pcapStats.PacketsIfDropped)
                        }
                        
		cAssembledPackets = 0
		cExpiredPackets   = 0
		cValidPackets     = 0
		cWaitQueuePackets = 0
		cMqueueErrors     = 0

		cTotalPackets = totalPackets
		for idx:=0; idx<*threads; idx++ {
		  cAssembledPackets += assembledPackets[idx]
		  cExpiredPackets += expiredPackets[idx]
		  cValidPackets += validPackets[idx]
		  cMqueueErrors += mqErrorCounters[idx]
		  cWaitQueuePackets += uint64(len(mysqlPacketChannels[idx]))
		}

		dt := time.Now().String()
		
		fmt.Printf("%s %15d %15d  %15d  %15d   %15d %15d  %15d %15d \n", dt[0:19], 
                uint64((cTotalPackets - pTotalPackets) / STATUS_INTERVAL_SECOND),
                uint64((cValidPackets - pValidPackets) / STATUS_INTERVAL_SECOND),
                uint64((cPacketDropped - pPacketDropped) / STATUS_INTERVAL_SECOND),
                uint64((cPacketIfDropped - pPacketIfDropped) / STATUS_INTERVAL_SECOND),
                uint64((cAssembledPackets - pAssembledPackets) / STATUS_INTERVAL_SECOND),
                uint64((cExpiredPackets - pExpiredPackets) / STATUS_INTERVAL_SECOND),
				cWaitQueuePackets,
                uint64((cMqueueErrors - pMqueueErrors) / STATUS_INTERVAL_SECOND))
		
		pTotalPackets     = cTotalPackets
		pValidPackets     = cValidPackets
		pPacketDropped    = cPacketDropped
		pPacketIfDropped  = cPacketIfDropped
		pAssembledPackets = cAssembledPackets
		pExpiredPackets   = cExpiredPackets  
		pMqueueErrors     = cMqueueErrors  
		
		// 오래된 쓰레기 패킷 데이터를 삭제하는 작업 수행(이 처리를 위해서 시간이 걸릴수 있지만, ...)
		workerIdx := loopCounter % *threads
		flushExpiredRequest(workerIdx) // Flush expired packet by shard
		checkMemoryUsage(int64(512) * 1024 * 1024) // Limit memory usage as 512MB
		
		// 20초에 한번씩 MySQL 서버의 모든 연결에 대해서 현재 접속된 DB정보를 MongoDB Queue로 전송
		if loopCounter % 20 == 0 {
			sendConnectionDefaultDatabase()
		}
		
		loopCounter++
		
		elapsedNanoSeconds := time.Since(startTime)
		time.Sleep(time.Second * time.Duration(STATUS_INTERVAL_SECOND) - elapsedNanoSeconds)
	}
}

/**
 * MRTECollector의 메인 엔트리 포인트
 */
func main() {
	// 버전 출력
	log.Printf("%s - %s\n\n", PROGNAME, VERSION)
	
	// 프로그램 파라미터 파싱
	flag.Parse()
	
	// DEFINE GLOBAL VARS
	queueTimeoutSeconds = time.Second * 5
	mysqlPacketChannels = make([]chan *MysqlPacket, *threads) // Make array of channel
	for idx:=0; idx<*threads; idx++ {
		mysqlPacketChannels[idx] = make(chan *MysqlPacket, channelBufferSize)
	}
	mysqlRequestMaps = make([]map[string]mrte.MysqlRequest, *threads)
	for idx:=0; idx<*threads; idx++ {
		mysqlRequestMaps[idx] = make(map[string]mrte.MysqlRequest)
	}
	
	for idx:=0; idx<*threads; idx++ {
		  assembledPackets = make([]uint64, *threads)
		  expiredPackets = make([]uint64, *threads)
		  validPackets = make([]uint64, *threads)
		  mqErrorCounters = make([]uint64, *threads)
	}
	// 디버그 용도의 코드
	// tempQueue = make(chan mrte.MysqlRequest)
	
	// 외부 큐로 사용되는 MongoDB 서버 연결 초기화
	mongoConnection := mrte.InitConnection(*mongo_uri)
	defer mongoConnection.Close()
	mrte.PrepareCollections(*mongo_db, *mongo_collection, *mongo_capped_size, *threads/* =mongo_collections*/)
	
	// 패킷 캡쳐 라이브러리 초기화
	pcapFilter := fmt.Sprintf("tcp dst port %d", *port)
	log.Printf("Starting capture on interface %q with '%s' BPF-filter", *iface, pcapFilter)

// =================================================================================
// gopacket library는 PacketDropped가 많이 발생해서, miekg library를 이용해서 패킷 캡쳐하도록 우회
// =================================================================================
//	handle, err := pcap.OpenLive(*iface, int32(*snaplen), true, time.Second * 5)
//	if err != nil {
//		log.Fatal("Error opening pcap handle: ", err)
//	}
//	if err := handle.SetBPFFilter(*pcapFilter); err != nil {
//		log.Fatal("Error setting BPF filter: ", err)
//	}
//	
//	// LinkType 확인, 일반적인 네트워크 환경에서는 LinkType은 1(LinkTypeEthernet)이어야 함.
//	linkType = int(handle.LinkType())
// =================================================================================

	handle, err := pcap.Create(*iface)
	if handle==nil {
		log.Fatal("Failed to initialize packet capture library")
	}
        
	err = handle.SetSnapLen(int32(*snaplen))
	if err!=nil {
		log.Fatal("Failed to set snapshot length")
	}
        
	err = handle.SetReadTimeout(int32(100))
	if err!=nil {
		log.Fatal("Failed to set read timeout")
	}

	err = handle.SetBufferSize(int32(32*1024*1024))
	if err!=nil {
		log.Fatal("Failed to set buffer size")
	}

	err = handle.Activate()
	if err!=nil {
		log.Fatal("Failed to activate packet capture library")
	}

	err = handle.SetDirection("in")
	if err!=nil {
		log.Fatal("Failed to set packet direction")
	}
	
	err = handle.SetFilter(pcapFilter)
	if err != nil {
		log.Fatal("Failed to set pcap filter")
	}
	
	// Get data link type
	linkType = handle.Datalink()
// =================================================================================

	// 패킷을 조립해서 외부 큐(MongoDB)로 전달해주는 쓰레드 초기화
	// threads 파라미터로 설정된 값만큼의 쓰레드 생성
	for idx:=0; idx<*threads; idx++ {
		go sendUserPacket(idx)
	}
	// 디버그 용도의 코드
	// go dequeueUserPacket()
	
	// 처리 내역 출력을 위한 쓰레드 생성
	go printProcessStatus(handle)

	// 패킷 캡쳐 쓰레드 시작
// =================================================================================
// gopacket library는 PacketDropped가 많이 발생해서, miekg library를 이용해서 패킷 캡쳐하도록 우회
// =================================================================================
//	for {
//		// To speed things up, we're also using the ZeroCopy method for
//		// reading packet data.  This method is faster than the normal
//		// ReadPacketData, but the returned bytes in 'data' are
//		// invalidated by any subsequent ZeroCopyReadPacketData call.
//		// Note that tcpassembly is entirely compatible with this packet
//		// reading method.  This is another trade-off which might be
//		// appropriate for high-throughput sniffing:  it avoids a packet
//		// copy, but its cost is much more careful handling of the
//		// resulting byte slice.
//		data, _/*ci*/, err := handle.ZeroCopyReadPacketData()
//
//		if err != nil {
//			log.Printf("Error getting packet: %v", err)
//			continue
//		}
//		totalPackets++
//		
//		// TCP/IP 패킷의 전체 크기로 Application Layer Payload를 가지고 있는지 아닌지 판단해서,
//		// 응용 프로그램 레이어의 데이터를 가지지 않고 단순히 TCP/IP 컨트롤 전용의 패킷인 경우 더이상 분석하지 않고 버린다.
//		// 일반적으로 카카오 내부 네트워크 환경에서 서버끼리 주고 받는 패킷의 TCP IP 헤더 길이는 0x42(=66) 바이트이므로, 패킷의 데이터 길이가 0x42보다 작거나 같으면 그냥 스킵한다.
//		// 
//		// **주의** 만약 MRTECollector가 SQL 캡쳐를 제대로 못한다면, MRTECollector 개발환경의 네트워크 구성과 차이가 있을수 있으니, 이 옵션(fastfilter)를 false로 설정후 확인하도록 하자.
//		if *fastPacketParsing && len(data)<=0x42 {
//			continue
//		}
//			
//		assembleUserPacket(data)
//	}
// =================================================================================
	for pkt, r := handle.NextEx(); r >= 0; pkt, r = handle.NextEx() {
		if r==0 {
			// 패킷 캡쳐 라이브러리 타임아웃, 무시하고 계속 반복 루프 실행
		}else{
			totalPackets++
			if *fastPacketParsing && len(pkt.Data)<=0x42 {
				continue
			}

			assembleUserPacket(pkt.Data)
		}
	}
// =================================================================================

	log.Println("Processing done")
}

/**
 * MRTECollector가 사용중인 메모리 크기 확인 및 메모리 사용량 제한
 * 메모리 사용량이 512MB를 넘어서면, MRTECollector를 강종
 */
const (
	statm_size = iota
	statm_resident
	statm_share
	statm_text
	statm_lib
	statm_data
	statm_dt /* over 2.6 */
	STATM_FIELD_END
)

func checkMemoryUsage(limit int64)(){
	var residentMemory int64
	var procStatContents string
	pageSize := int64(syscall.Getpagesize())
	if b, e := ioutil.ReadFile("/proc/self/statm"); e == nil {
		procStatContents = string(b)
	}
        
	fields := strings.Fields(procStatContents)
	if len(fields) >= (STATM_FIELD_END-1) {
		if stat_value, e := strconv.ParseInt(fields[statm_resident], 10, 64); e == nil {
			residentMemory = stat_value * pageSize
		}
	}
        
	if(residentMemory > limit){
		panic("Memory usage is too high ("+strconv.FormatInt(residentMemory,10)+" > "+strconv.FormatInt(limit,10)+"), Increase memory limit or need to decrease memory usage")
	}
}
