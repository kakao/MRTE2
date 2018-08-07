package mrte

import (
	"encoding/binary"
	"time"
	"errors"
)

const ( 
        TYPE_IP  = 0x0800
        TYPE_ARP = 0x0806
        TYPE_IP6 = 0x86DD
        
        IP_ICMP = 1
        IP_INIP = 4
        IP_TCP  = 6
        IP_UDP  = 17
)

// Port from sf-pcap.c file.
const ( 
        TCPDUMP_MAGIC           = 0xa1b2c3d4
        KUZNETZOV_TCPDUMP_MAGIC = 0xa1b2cd34
        FMESQUITA_TCPDUMP_MAGIC = 0xa1b234cd
        NAVTEL_TCPDUMP_MAGIC    = 0xa12b3c4d
        NSEC_TCPDUMP_MAGIC      = 0xa1b23c4d
)

// DLT,
// these are the types that are the same on all platforms, and that
// have been defined by <net/bpf.h> for ages.
const ( 
        DLT_NULL    = 0  // BSD loopback encapsulation
        DLT_EN10MB  = 1  // Ethernet (10Mb)
        DLT_EN3MB   = 2  // Experimental Ethernet (3Mb)
        DLT_AX25    = 3  // Amateur Radio AX.25
        DLT_PRONET  = 4  // Proteon ProNET Token Ring
        DLT_CHAOS   = 5  // Chaos
        DLT_IEEE802 = 6  // 802.5 Token Ring
        DLT_ARCNET  = 7  // ARCNET, with BSD-style header
        DLT_SLIP    = 8  // Serial Line IP
        DLT_PPP     = 9  // Point-to-point Protocol
        DLT_FDDI    = 10 // FDDI
        DLT_RAW     = 12 // raw IP , DLT_LAW is defined as 14 in BSD(Including OpenBSD), But we don't care for that OS */
                         // And This could not be a IP4 or IP6, But just regarding this packet as tcp/ip packet
        DLT_IPV4    = 228 // Raw IPv4
        DLT_IPV6    = 229 // Raw IPv6
)

const (
        ERRBUF_SIZE = 256

        // According to pcap-linktype(7).
        LINKTYPE_NULL       = DLT_NULL
        LINKTYPE_ETHERNET   = DLT_EN10MB
        LINKTYPE_TOKEN_RING = DLT_IEEE802

        LINKTYPE_EXP_ETHERNET = DLT_EN3MB /* 3Mb experimental Ethernet */
        LINKTYPE_AX25         = DLT_AX25
        LINKTYPE_PRONET       = DLT_PRONET
        LINKTYPE_CHAOS        = DLT_CHAOS
        LINKTYPE_ARCNET_BSD   = DLT_ARCNET /* BSD-style headers */
        LINKTYPE_SLIP         = DLT_SLIP
        LINKTYPE_PPP          = DLT_PPP
        LINKTYPE_FDDI         = DLT_FDDI

        LINKTYPE_ARCNET           = 7
        LINKTYPE_ATM_RFC1483      = 100
        LINKTYPE_RAW              = 101
        LINKTYPE_PPP_HDLC         = 50
        LINKTYPE_PPP_ETHER        = 51
        LINKTYPE_C_HDLC           = 104
        LINKTYPE_IEEE802_11       = 105
        LINKTYPE_FRELAY           = 107
        LINKTYPE_LOOP             = 108
        LINKTYPE_LINUX_SLL        = 113
        LINKTYPE_LTALK            = 104
        LINKTYPE_PFLOG            = 117
        LINKTYPE_PRISM_HEADER     = 119
        LINKTYPE_IP_OVER_FC       = 122
        LINKTYPE_SUNATM           = 123
        LINKTYPE_IEEE802_11_RADIO = 127
        LINKTYPE_ARCNET_LINUX     = 129
        LINKTYPE_LINUX_IRDA       = 144
        LINKTYPE_LINUX_LAPD       = 177
)

const (
        TCP_FIN = 1 << iota
        TCP_SYN
        TCP_RST
        TCP_PSH
        TCP_ACK
        TCP_URG
        TCP_ECE
        TCP_CWR
        TCP_NS
)

// Lookahead port no before Exact parsing.
// This may not exact port no
// And this function must be faster than full parsing (this is called in single pipeline)
func GetPortNo(data []byte, dlt int) (port uint16){
	ethernetHeaderLength := 0
	if dlt==DLT_EN10MB || dlt==DLT_EN3MB {
		// 1. Ethernet Header
		//     ethernet headers are always exactly 14 bytes */
		//     #define SIZE_ETHERNET 14
		ethernetHeaderLength = 14
	}

	// 2. Ip Header
	//    Protocol Version(4 bits) : This is the first field in the protocol header. This field occupies 4 bits. This signifies the current IP protocol version being used. Most common version of IP protocol being used is version 4 while version 6 is out in market and fast gaining popularity.
	//    Header Length(4 bits) : This field provides the length of the IP header. The length of the header is represented in 32 bit words. This length also includes IP options (if any). Since this field is of 4 bits so the maximum header length allowed is 60 bytes. Usually when no options are present then the value of this field is 5. Here 5 means five 32 bit words ie 5 *4 = 20 bytes.
	//    Type of service(8 bits) : The first three bits of this field are known as precedence bits and are ignored as of today. The next 4 bits represent type of service and the last bit is left unused. The 4 bits that represent TOS are : minimize delay, maximize throughput, maximize reliability and minimize monetary cost.
	//    Total length(16 bits): This represents the total IP datagram length in bytes. Since the header length (described above) gives the length of header and this field gives total length so the length of data and its starting point can easily be calculated using these two fields. Since this is a 16 bit field and it represents length of IP datagram so the maximum size of IP datagram can be 65535 bytes. When IP fragmentation takes place over the network then value of this field also changes. There are cases when IP datagrams are very small in length but some data links like ethernet pad these small frames to be of a minimum length ie 46 bytes. So to know the exact length of IP header in case of ethernet padding this field comes in handy.
	ipHeaderWordLength := uint8(data[ethernetHeaderLength + 0]) & 0x0F
	ipHeaderLength := int(ipHeaderWordLength) * 4

	// 3. Tcp Header
	portIdx := ethernetHeaderLength + ipHeaderLength
	if (portIdx+2)>len(data) { // There's no port area on this packet
		return 0
	}

	return binary.BigEndian.Uint16(data[portIdx:portIdx+2])
}

// Decode decodes the headers of a Packet.
// all method (parseIp and parseTcp) is merged into Parse()
func ParsePacketFast(packetData []byte, dlt int) (*MysqlRequest, error){
	if len(packetData) < 14 { // Length of Ethernet header
		return nil, errors.New("Packet is too small (less than ethernet-header")
	}
 
	var packetType int
	var payload []byte
	if dlt==DLT_EN10MB || dlt==DLT_EN3MB {
		packetType = int(binary.BigEndian.Uint16(packetData[12:14]))
		payload = packetData[14:] // Strip ethernet header.
	}else{
		// IP-Tunneling use DLT_RAW data-link type, So there's no ethernet header
		// But still regarding it's as TCP/IP packet 
		packetType = TYPE_IP        // Just regarding this packet as TCP/IP packet
		payload = packetData        // If packet is raw | ipv4 | ipv6, then there's not ethernet header.
	}

	if packetType == TYPE_IP {
		if len(payload) < 20 {
			return nil, errors.New("Packet is too small (less than ip-header")
		}
        // p.Payload = payload 임
        
        // Parse IP part
		Ihl := uint8(payload[0]) & 0x0F
		ipLength := binary.BigEndian.Uint16(payload[2:4])
		Protocol := payload[9]
		SrcIp := payload[12:16]

		pEnd := int(ipLength)
		if pEnd > len(payload) {
			pEnd = len(payload)
		}
		pIhl := int(Ihl) * 4
		if pIhl > pEnd {
			pIhl = pEnd
		}
		payload = payload[pIhl:pEnd]
        
		if Protocol == IP_TCP {
			// Parse TCP part
			pLenPayload := len(payload)
			if pLenPayload < 20 {
				return nil, errors.New("Packet is too small (less than tcp-header")
			}

			TcpSrcPort := int32(binary.BigEndian.Uint16(payload[0:2]))
			TcpDataOffset := (payload[12] & 0xF0) >> 4
			pDataOffset := int(TcpDataOffset * 4)
			if pDataOffset > pLenPayload {
				pDataOffset = pLenPayload
			}
			tcpPayload := payload[pDataOffset:]
// =================================================================================
// miekg library에서는 이미 복사된 메모리 공간에 캡쳐된 패킷을 저장하기 때문에, 별도의 복사 작업 불필요
// =================================================================================
//			CopiedTcpPayload := make([]byte, len(TcpPayload))
//			copy(CopiedTcpPayload, TcpPayload)
	
			return &MysqlRequest{AlreadyParsed:false, SrcIp:ipToString(SrcIp), SrcPort:TcpSrcPort, Data:tcpPayload, CapturedAt:time.Now()}, nil
		// -------------------------------------------
		}else{
			return nil, errors.New("Not a tcp packet")
		}
	// -----------------------------------------------
	}else{
		return nil, errors.New("Not a ip packet")
	}
}


func ipToString(ip []byte) string {
	if len(ip)<4 {
		return ""
	}
	
	return uitoa(uint(ip[0])) + "." +
		uitoa(uint(ip[1])) + "." +
		uitoa(uint(ip[2])) + "." +
		uitoa(uint(ip[3]))
}

func uitoa(val uint) string {
	var buf [32]byte // big enough for int64
	i := len(buf) - 1
	for val >= 10 {
		buf[i] = byte(val%10 + '0')
		i--
		val /= 10
	}
	buf[i] = byte(val + '0')
	return string(buf[i:])
}