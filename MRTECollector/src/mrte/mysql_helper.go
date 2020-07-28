package mrte

import (
        "fmt"
        "strings"
        "strconv"
        "database/sql"
		"github.com/globalsign/mgo/bson"
        _ "github.com/go-sql-driver/mysql"
)

var replThreadStates = [...]string{
        "Waiting for main", // Waiting for main to send event
        "Has read all relay log", // Has read all relay log; waiting for the subordinate I/O thread t
        "Waiting on empty queue",
        "Main has sent all binlog", // Main has sent all binlog to subordinate; waiting for binlog to be up
}

type ConnectionInfo struct {
	SrcIp   string
	SrcPort int32  // port는 uint16 타입이지만, Java나 MongoDB 연동을 위해서 int32 타입으로 관리한다.
	ParsedRequest *bson.M
}

func GetSessionDefaultDatabase(uri string) []ConnectionInfo{
	db, err := sql.Open("mysql", uri)
	if err != nil {
		fmt.Printf("Can not connect to MySQL Server : %v\n", err)
		return nil // 에러가 발생하면, 로그만 출력하고 무시
	}
    
	defer db.Close()

	rows, err := db.Query("select host, db, state from information_schema.processlist where id<>connection_id()") /* Exception current connection */
	if err != nil {
		fmt.Printf("Can not execute query : %v\n", err)
		return nil // 에러가 발생하면, 로그만 출력하고 무시
	}
	defer rows.Close()

	sessions := make([]ConnectionInfo, 0)
	
	// 레코드 페치를 위해서 Column iInterface를 준비
	cols, err := rows.Columns()
	if err != nil {
		fmt.Printf("Can not fetch column information from result set : %v\n", err)
		return nil // 에러가 발생하면, 로그만 출력하고 무시
	}

	rawResult := make([][]byte, len(cols))
	result := make([]string, len(cols))

	dest := make([]interface{}, len(cols))
	for i, _ := range rawResult {
		dest[i] = &rawResult[i] // Put pointers to each string in the interface slice
	}
        
	// 레코드 데이터를 페치
	for rows.Next() {
		err = rows.Scan(dest...)
		if err !=nil {
		fmt.Printf("Can not fetch record from result set : %v\n", err)
		return nil // 에러가 발생하면, 로그만 출력하고 무시
		}
                
		for i, raw := range rawResult {
			if raw == nil {
				result[i] = ""
			} else {
				result[i] = string(raw)
			}
		}
                
		if len(result[2])>0 {
			isReplThread := false
			for idx:=0; idx<len(replThreadStates); idx++ {
				if strings.HasPrefix(result[2], replThreadStates[idx]) {
					isReplThread = true
				}
			}
        
        	// Replication Thread는 모두 무시한다.                
			if isReplThread {
				continue // 무시
			}
		}

		if len(result[0])>0 && len(result[1])>0 {
			if strings.HasPrefix(result[0], "127.0.0.1") || strings.HasPrefix(result[0], "localhost") {
				continue
			}
			
			hostAndPort := strings.Split(string(result[0]), ":")
 			if len(hostAndPort)!=2 {
				continue;
			}
 			
 			port, err := strconv.Atoi(hostAndPort[1])
 			if err!=nil {
 				// 그냥 무시
 			}else{
	 			info := ConnectionInfo{SrcIp: hostAndPort[0], SrcPort: int32(port), ParsedRequest: &bson.M{"c":COM_INIT_DB, "q":result[1]}}
	 			sessions = append(sessions, info)
			}
		}
	}
        
	return sessions
}