package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"strings"
	"unicode/utf16"
)

// escapeJSONToASCII converts any non-ASCII runes in valid JSON into \uXXXX escapes
// so JNI NewStringUTF (which requires Modified UTF-8) can never abort on 4-byte UTF-8.
func escapeJSONToASCII(jsonStr string) string {
	var b strings.Builder
	b.Grow(len(jsonStr))
	for _, r := range jsonStr {
		if r < 0x80 {
			b.WriteRune(r)
		} else if r <= 0xFFFF {
			fmt.Fprintf(&b, "\\u%04x", r)
		} else {
			r1, r2 := utf16.EncodeRune(r)
			fmt.Fprintf(&b, "\\u%04x\\u%04x", r1, r2)
		}
	}
	return b.String()
}

func marshalJSONCString(v any) *C.char {
	b, err := json.Marshal(v)
	if err != nil {
		fallback := fmt.Sprintf(`{"success":false,"error":%q}`, err.Error())
		return C.CString(escapeJSONToASCII(fallback))
	}
	return C.CString(escapeJSONToASCII(string(b)))
}

//export wg_start_tunnel
func wg_start_tunnel(uapiConfig *C.char, localAddrsCsv *C.char, dnsCsv *C.char, mtu C.int) (res *C.char) {
	defer func() {
		if r := recover(); r != nil {
			res = marshalJSONCString(StartResult{
				Success: false,
				Error:   fmt.Sprintf("wireguard panic: %v", r),
			})
		}
	}()
	result := globalTunnel.Start(
		C.GoString(uapiConfig),
		C.GoString(localAddrsCsv),
		C.GoString(dnsCsv),
		int(mtu),
	)
	return marshalJSONCString(result)
}

//export wg_stop_tunnel
func wg_stop_tunnel() (res *C.char) {
	defer func() {
		if r := recover(); r != nil {
			res = marshalJSONCString(StatusResult{
				Running: false,
				Error:   fmt.Sprintf("wireguard stop panic: %v", r),
			})
		}
	}()
	return marshalJSONCString(globalTunnel.Stop())
}

//export wg_get_status
func wg_get_status() (res *C.char) {
	defer func() {
		if r := recover(); r != nil {
			res = marshalJSONCString(StatusResult{
				Running: false,
				Error:   fmt.Sprintf("wireguard status panic: %v", r),
			})
		}
	}()
	return marshalJSONCString(globalTunnel.Status())
}

//export wg_dial_test
func wg_dial_test(hostPort *C.char, timeoutMs C.int) (res *C.char) {
	defer func() {
		if r := recover(); r != nil {
			res = marshalJSONCString(DialTestResult{
				Success: false,
				Error:   fmt.Sprintf("wireguard dial panic: %v", r),
			})
		}
	}()
	return marshalJSONCString(globalTunnel.DialTest(C.GoString(hostPort), int(timeoutMs)))
}

func main() {}
