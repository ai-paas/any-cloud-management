package controller

import "encoding/json"

// jsonDecode — 별도 파일로 추출. dispatcher.go 가 직접 encoding/json 을 import 하면 명령
// 추가마다 import 가 늘어나서 readability 저하. helper 를 좁게 유지.
func jsonDecode(data []byte, out *map[string]interface{}) error {
	return json.Unmarshal(data, out)
}

// jsonDecodeStrSlice — include_paths 같은 string[] 파라미터 디코드.
func jsonDecodeStrSlice(data []byte, out *[]string) error {
	return json.Unmarshal(data, out)
}
