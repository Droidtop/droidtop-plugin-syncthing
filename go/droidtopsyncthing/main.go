// Copyright (C) 2026 bi0shacker001
// SPDX-License-Identifier: GPL-3.0-or-later
//
// droidtop's Syncthing: upstream Syncthing's own library (lib/syncthing), built
// as a c-shared library for Android and driven by the plugin's Kotlin code over
// one JNI call, JSON in and JSON out (README.md, "How it fits together").
//
// build.sh copies this package into the pinned upstream checkout
// (cmd/droidtopsyncthing) and builds it there, so it compiles against exactly
// the upstream go.mod and go.sum and changes no upstream file.
package main

/*
#include <jni.h>
#include <stdlib.h>

static jbyteArray dt_new_bytes(JNIEnv *env, const void *data, jsize n) {
	jbyteArray array = (*env)->NewByteArray(env, n);
	if (array != NULL && n > 0) (*env)->SetByteArrayRegion(env, array, 0, n, (const jbyte *) data);
	return array;
}

static jsize dt_length(JNIEnv *env, jbyteArray array) {
	return array == NULL ? 0 : (*env)->GetArrayLength(env, array);
}

static void dt_read(JNIEnv *env, jbyteArray array, void *out, jsize n) {
	(*env)->GetByteArrayRegion(env, array, 0, n, (jbyte *) out);
}
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"unsafe"
)

func main() {}

// Java_dev_droidtop_plugins_syncthing_SyncthingNative_call is SyncthingNative.call(op, args): both UTF-8 bytes, the
// reply a JSON object {"ok": true, "data": {...}} or {"ok": false, "error": "..."}. Byte arrays rather than strings,
// because JNI's string calls speak modified UTF-8 and a device name may hold any character.
//
//export Java_dev_droidtop_plugins_syncthing_SyncthingNative_call
func Java_dev_droidtop_plugins_syncthing_SyncthingNative_call(env *C.JNIEnv, clazz C.jclass, op C.jbyteArray, args C.jbyteArray) C.jbyteArray {
	reply := dispatch(string(readBytes(env, op)), readBytes(env, args))
	if len(reply) == 0 {
		return C.dt_new_bytes(env, nil, 0)
	}
	return C.dt_new_bytes(env, unsafe.Pointer(&reply[0]), C.jsize(len(reply)))
}

func readBytes(env *C.JNIEnv, array C.jbyteArray) []byte {
	n := C.dt_length(env, array)
	if n <= 0 {
		return nil
	}
	out := make([]byte, int(n))
	C.dt_read(env, array, unsafe.Pointer(&out[0]), n)
	return out
}

// dispatch runs one op and encodes its reply; a panic is an error reply, never a crash of droidtop's plugin process.
func dispatch(op string, args []byte) (reply []byte) {
	defer func() {
		if r := recover(); r != nil {
			reply = encodeError(fmt.Errorf("%s: %v", op, r))
		}
	}()
	handler, ok := ops[op]
	if !ok {
		return encodeError(fmt.Errorf("unknown op %q", op))
	}
	if len(args) == 0 {
		args = []byte("{}")
	}
	data, err := handler(args)
	if err != nil {
		return encodeError(err)
	}
	if data == nil {
		data = map[string]any{}
	}
	out, err := json.Marshal(map[string]any{"ok": true, "data": data})
	if err != nil {
		return encodeError(err)
	}
	return out
}

func encodeError(err error) []byte {
	out, _ := json.Marshal(map[string]any{"ok": false, "error": err.Error()})
	return out
}
