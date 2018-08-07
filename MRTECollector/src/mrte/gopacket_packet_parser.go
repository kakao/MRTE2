package mrte

import (
	"time"
	"errors"
	"github.com/google/gopacket"
	"github.com/google/gopacket/layers"
)

func ParsePacketSlow(decoded []gopacket.LayerType, ip4 *layers.IPv4, ip6 *layers.IPv6, tcp *layers.TCP) (*MysqlRequest, error){
	// IPv4와 IPv5 레이어의 TCP 패킷 캡쳐 및 분석 
	foundNetLayer := false
	var netFlow gopacket.Flow
	for _, typ := range decoded {
		switch typ {
		case layers.LayerTypeIPv4:
			netFlow = ip4.NetworkFlow()
			foundNetLayer = true
		case layers.LayerTypeIPv6:
			netFlow = ip6.NetworkFlow()
			foundNetLayer = true
		case layers.LayerTypeTCP:
			if foundNetLayer {
				// assembleUserPacket(netFlow, &tcp, ci.Timestamp)
				if len(tcp.LayerPayload()) == 0 {
					// 사용자 데이터를 가지지 않은 TCP Control Packet은 무시
					return nil, nil
				}
			
				data := tcp.Payload
				srcIp := netFlow.Src().String()
				srcPort := int32(tcp.SrcPort)
				if srcPort<0 { // 양수로 변환
					srcPort *= -1
				}

				mp := MysqlRequest{AlreadyParsed:false, SrcIp: srcIp, SrcPort: srcPort, Data:data, CapturedAt: time.Now()}
				
				return &mp, nil
			}
			break
		}
	}
	
	return nil, errors.New("Could not find IPv4 or IPv6 or Tcp layer")
}


