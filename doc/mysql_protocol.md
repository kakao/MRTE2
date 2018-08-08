### MySQL Client-Server Protocol

##### Protocol type
###### Connection Phase
1. Open socket (client -> server)
2. Initial Handshake Request (server -> client)
3. Handshake Response (client -> server)

###### Text Protocol & Binary Protocol
1. Command Request (client -> server)
2. Command Response (server -> client)<br>
   ...<br>
   ...

###### Text Protocol
- 모든 데이터 타입의 값들이 String(Length encoded)으로 변환후 전달됨
- 일반적으로 전송해야 할 데이터 크기 증가 & 변환 작업 필요
- Little/Big endian에 대한 고려 불필요
- https://dev.mysql.com/doc/internals/en/protocoltext-resultset.html

###### Binary Protocol
- Serverside prepared statement에서 사용됨
- 모든 데이터 타입에 대한 바이트 값을 그대로 전달
- 바이트 값을 그대로 전송하므로 변환 작업 불필요
- Little/Big endian에 대한 고려 필요
- https://dev.mysql.com/doc/internals/en/binary-protocol-resultset.html

###### Text vs Binary Protocol difference
- INT32(-987654321)
```
  - BINARY PROTOCOL : 0x4f 0x97 0x21 0xc5
  - TEXT PROTOCOL   : 0x0a 0x39 0x38 0x37 0x36 0x35 0x34 0x33 0x32 0x31
    0x0a -> '-'
    0x39 -> '9'
    0x38 -> '8'
    ...
```

##### Packet (Common Header)
https://dev.mysql.com/doc/internals/en/mysql-packet.html

| Type | Name | Description |
|------|------|-------------|
|int<3>|payload_length|Length of the payload. The number of bytes in the packet beyond the initial 4 bytes that make up the packet header.|
|int<1>|sequence_id|Sequence Id|
|byte< len >|payload|[len=payload_length] payload of the packet|

##### Packet (Handshake Response)
https://dev.mysql.com/doc/internals/en/connection-phase-packets.html#packet-Protocol::HandshakeResponse

```
4              capability flags, CLIENT_PROTOCOL_41 always set
4              max-packet size
1              character set
string[23]     reserved (all [0])
string[NUL]    username
  if capabilities & CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA {
lenenc-int     length of auth-response
string[n]      auth-response
  } else if capabilities & CLIENT_SECURE_CONNECTION {
1              length of auth-response
string[n]      auth-response
  } else {
string[NUL]    auth-response
  }
  if capabilities & CLIENT_CONNECT_WITH_DB {
string[NUL]    database
  }
  if capabilities & CLIENT_PLUGIN_AUTH {
string[NUL]    auth plugin name
  }
  if capabilities & CLIENT_CONNECT_ATTRS {
lenenc-int     length of all key-values
lenenc-str     key
lenenc-str     value
   if-more data in 'length of all key-values', more keys and value pairs
  }
```

##### Packet (Text Protocol)
https://dev.mysql.com/doc/internals/en/text-protocol.html

|Type | Name | Description |
|-----|------|-------------|
|int<1>|command| ... |
|byte< len-1 >|data| ... |



<br><br><br>
##### Packet (Text Protocol) Examples

###### COM_QUIT    :
```
01 00 00 00 01
```

###### COM_INIT_DB :
```
05 00 00 00 02 74 65 73    74                         .....test
```

###### COM_QUERY :
```
21 00 00 00 03 73 65 6c    65 63 74 20 40 40 76 65    !....select @@ve
72 73 69 6f 6e 5f 63 6f    6d 6d 65 6e 74 20 6c 69    rsion_comment li
6d 69 74 20 31                                        mit 1
