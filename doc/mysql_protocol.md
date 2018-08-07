###MySQL Client-Server Protocol

#####Common Header
https://dev.mysql.com/doc/internals/en/mysql-packet.html

| Type | Name | Description |
|------|------|-------------|
|int<3>|payload_length|Length of the payload. The number of bytes in the packet beyond the initial 4 bytes that make up the packet header.|
|int<1>|sequence_id|Sequence Id|
|byte< len >|payload|[len=payload_length] payload of the packet|

#####Initial Handshake
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

#####Text Protocol
https://dev.mysql.com/doc/internals/en/text-protocol.html

|Type | Name | Description |
|-----|------|-------------|
|int<1>|command| ... |
|byte< len-1 >|data| ... |



#####Command Examples


######COM_QUIT    : 
```
01 00 00 00 01
```

######COM_INIT_DB : 
```
05 00 00 00 02 74 65 73    74                         .....test
```

######COM_QUERY : 
```
21 00 00 00 03 73 65 6c    65 63 74 20 40 40 76 65    !....select @@ve
72 73 69 6f 6e 5f 63 6f    6d 6d 65 6e 74 20 6c 69    rsion_comment li
6d 69 74 20 31                                        mit 1
```

