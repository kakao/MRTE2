package mrte

import (
	// "fmt"
	"strings"
	"errors"
	"encoding/hex"
	// "github.com/globalsign/mgo"
	"github.com/globalsign/mgo/bson"
)

/**
 * MySQL Protocol Constants
 */
const COM_QUIT int               = 0x01
const COM_INIT_DB int            = 0x02
const COM_QUERY int              = 0x03
const COM_SET_OPTION int         = 0x1b
// COM_LOGIN과 COM_UNKNOWN 상수 값은 MySQL Protocol에 정의된 값이 아니라, 이 프로그램에서 정의된 임의 값임
const COM_LOGIN                  = 0x7e /* This is artificial command */
const COM_UNKNOWN                = 0x7f /* This is artificial command */

/**
 * 일반적으로 SQL Statement를 실행하는 Packet의 sequence 번호는 0임
 * 만약 이 seuqence 값이 1보다 크거나 같다면, Login 과정에서 전송된 패킷인데,
 * 일반적으로 id/password 오류가 아니라면, sequence 값은 1인 경우가 대부분임
 */ 
const SEQUENCE_FOR_LOGIN int     = 0x01

/**
 * SQL Statement가 SELECT(SET ..., SHOW ... 구문 포함)인지 아닌지를 식별
 * 이 함수는 MRTECollector가 SELECT 문장만 Queue로 전달할지 여부를 판단하기 위해서 사용됨 
 *   isSelectStatement 함수는 SQL Statement의 주석 제거를 위해서 Recursive하게 실행되는데,
 *   이때 depth 변수는 Recursive하게 몇 depth 실행인지, 그리고 몇 단계 이상의 경우 포기하도록 하기 위해서 전달받는 변수임 
 */
func isSelectStatement(sql string, depth int) bool {
	if depth>4 { // Prevent infinite recursive call
		return false
	}
	
	depth++
	sql2 := strings.TrimSpace(sql)
	
	
	// Step-1 : Fast scaning
	//        : 먼저 SQL statement에 주석이 없다고 가정하고, SQL statement 제일 앞쪽 6 바이트를 비교
	if len(sql2)<6 { /* SQL 문장의 전체 길이가 6글자 미만이면, SELECT가 아닌 것으로 간주 */
		return false
	}
	header := strings.ToLower(sql2[0:6])
	
	if header=="select" || strings.HasPrefix(header, "desc") || strings.HasPrefix(header, "show") {
		return true
	}

	// Step-2 : Remove comment and check again
	//        : SQL Statement 앞쪽의 주석을 제거	
	singleLineComment := strings.Index(header, "--")
	multiLineComment := strings.Index(header, "/*")
	if singleLineComment==-1 && multiLineComment==-1 {
		return false
	}

	if singleLineComment>=0 { // 단일 라인 주석 ("--")인 경우
		endOfComment := strings.Index(sql2, "\n")
		if endOfComment==-1 {
			endOfComment = strings.Index(sql2, "\r")
		}
		
		if endOfComment==-1 {
			// SQL Syntax 오류인듯 함 (Single line comment로 시작했는데,유효한 토큰과 NewLine없이 SQL Statement가 끝나버림)
			return false
		}
	
		endOfComment+=2
		// Step-3 : Check again
		//        : Recursive하게 다시 isSelectStatement() 함수 호출해서 결과 리턴
		return isSelectStatement(sql2[endOfComment:], depth)
	}else if multiLineComment>=0 { // 멀티 라인 주석 ("/*")인 경우
		endOfComment := strings.Index(sql2, "*/")
		if endOfComment==-1 {
			// SQL Syntax 오류인듯 함 (Multi line comment로 시작했는데, 코멘트의 끝이 보이지 않음)
			return false
		}
	
		endOfComment+=2
		// Step-3 : Check again
		//        : Recursive하게 다시 isSelectStatement() 함수 호출해서 결과 리턴
		return isSelectStatement(sql2[endOfComment:], depth)
	}
	
	return false
}


/**
 * MySQL Packet에서 필요한 정보들을 추출해서 JSON 도큐먼트로 생성
 */
func ParsePacket(data []byte, onlySelect bool) (bson.M, error) {
	// 샘플 MySQL Packet 데이터
	// data := []byte{5, 0, 0, 0, 0x02, byte('m'), byte('y'), byte('d'), byte('b')}
	
	if len(data) <= 4 /* MySQL Packet은 최소 4바이트 이상 ==> MySQL Payload 사이즈(3바이트) + 시퀀스(1바이트) */ {
		return nil, errors.New("packet data is too short (must be length>4)")
	}
	
	sequence := int(data[3])
	command := int(COM_UNKNOWN)

	if sequence==SEQUENCE_FOR_LOGIN { // Login 과정의 패킷인 경우, Login data를 Hex string으로 변환해서 MongoDB로 저장 
		command = COM_LOGIN
		// Convert binary array to hex string
		statement := hex.EncodeToString(data[4:]) // Binary payload (skip length(3byte) + sequence(1byte)
		return bson.M{"c":COM_LOGIN, "q":statement},nil
	}else if sequence==0 { // Login이 아니라, 일반적인 쿼리 실행을 위한 패킷인 경우, Statement 부분을 잘라서 JSON으로 저장
		command = int(data[4])
		switch command {
			case COM_QUIT:
				// No statement
				return bson.M{"c":command, "q":""}, nil
			case COM_INIT_DB, COM_SET_OPTION :
				statement := string(data[5:]) // MySQL Payload 사이즈(3바이트) + 시퀀스(1바이트) + 커맨드(1바이트)는 무시하고, 그 이후의 데이터 영역만 MongoDB에 저장
				return bson.M{"c":command, "q":statement}, nil
			case COM_QUERY :
				statement := string(data[5:]) // MySQL Payload 사이즈(3바이트) + 시퀀스(1바이트) + 커맨드(1바이트)는 무시하고, 그 이후의 데이터 영역만 MongoDB에 저장
				if onlySelect {
					isSelectQuery := isSelectStatement(statement, 0)
					if !isSelectQuery {
						return nil, errors.New("this is not a select statement")
					}
				}
				return bson.M{"c":command, "q":statement}, nil
			default:
				// 그 이외의 Command인 경우, 모두 무시한다.
				return nil, errors.New("not interesting command")
		}
	}else{
		// MySQL packet의 sequence 값이 1보다 크다면, 이는 Login 과정중의 패킷일 가능성이 높은데, 이는 Login 실패시에만 가능한 케이스이다.
		// 서비스에서는 비밀번호 오류의 경우가 거의 없으니, 이 경우는 MRTECollector에서는 처리하지 않음.  
		return nil, errors.New("not support for 'sequence>1', looks like retry auth for failed-login-auth")
	}
}
