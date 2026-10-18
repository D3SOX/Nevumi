// Package main exposes a userspace Tailscale node to Nevumi's SFTP client through JNI.
package main

/*
#include <jni.h>
#include <stdlib.h>

static const char *string_chars(JNIEnv *env, jstring value) { return (*env)->GetStringUTFChars(env, value, NULL); }
static void release_chars(JNIEnv *env, jstring value, const char *chars) { (*env)->ReleaseStringUTFChars(env, value, chars); }
static jstring new_string(JNIEnv *env, const char *value) { return (*env)->NewStringUTF(env, value); }
static void get_bytes(JNIEnv *env, jbyteArray array, jint offset, jint length, void *buffer) {
	(*env)->GetByteArrayRegion(env, array, offset, length, (jbyte *)buffer);
}
static void set_bytes(JNIEnv *env, jbyteArray array, jint offset, jint length, const void *buffer) {
	(*env)->SetByteArrayRegion(env, array, offset, length, (const jbyte *)buffer);
}
static void throw_io(JNIEnv *env, const char *message) {
	jclass type = (*env)->FindClass(env, "java/io/IOException");
	if (type != NULL) (*env)->ThrowNew(env, type, message);
}
*/
import "C"

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"tailscale.com/envknob"
	"tailscale.com/tsnet"
	"tailscale.com/types/logger"
)

var (
	lock        sync.Mutex
	server      *tsnet.Server
	connections sync.Map
	nextHandle  atomic.Int64
)

var errNotConnected = errors.New("Tailscale is not connected")

func main() {}

func init() { envknob.SetNoLogsNoSupport() }

func goString(env *C.JNIEnv, value C.jstring) string {
	chars := C.string_chars(env, value)
	defer C.release_chars(env, value, chars)
	return C.GoString(chars)
}

func throw(env *C.JNIEnv, err error) {
	message := C.CString(err.Error())
	defer C.free(unsafe.Pointer(message))
	C.throw_io(env, message)
}

func start(directory, hostname, controlURL, authKey string) error {
	lock.Lock()
	defer lock.Unlock()
	if server != nil {
		if server.Dir == directory && server.ControlURL == controlURL && authKey == "" {
			return nil
		}
		server.Close()
		server = nil
	}
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	// Keeps Tailscale's local log state inside the node directory instead of a shared system or cache directory.
	os.Setenv("TS_LOGS_DIR", directory)
	candidate := &tsnet.Server{Dir: directory, Hostname: hostname, ControlURL: controlURL, AuthKey: authKey,
		Logf: logger.Discard, UserLogf: logger.Discard}
	if err := candidate.Start(); err != nil {
		candidate.Close()
		return err
	}
	server = candidate
	return nil
}

func current() (*tsnet.Server, error) {
	lock.Lock()
	defer lock.Unlock()
	if server == nil {
		return nil, errors.New("Tailscale is not started")
	}
	return server, nil
}

// status returns the backend state, login URL and node name separated by newlines.
func status() (string, error) {
	node, err := current()
	if err != nil {
		return "Stopped\n\n", nil
	}
	client, err := node.LocalClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	state, err := client.StatusWithoutPeers(ctx)
	if err != nil {
		return "", err
	}
	name := ""
	if state.Self != nil {
		name = strings.TrimSuffix(state.Self.DNSName, ".")
	}
	return state.BackendState + "\n" + state.AuthURL + "\n" + name, nil
}

func dial(host string, port int, timeout time.Duration) (int64, error) {
	node, err := current()
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	if _, err := node.Up(ctx); err != nil {
		return 0, errNotConnected
	}
	connection, err := node.Dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return 0, err
	}
	handle := nextHandle.Add(1)
	connections.Store(handle, connection)
	return handle, nil
}

func connection(handle C.jlong) (net.Conn, error) {
	value, ok := connections.Load(int64(handle))
	if !ok {
		return nil, net.ErrClosed
	}
	return value.(net.Conn), nil
}

func signOut() error {
	lock.Lock()
	defer lock.Unlock()
	if server == nil {
		return nil
	}
	directory := server.Dir
	if client, err := server.LocalClient(); err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		client.Logout(ctx)
		cancel()
	}
	server.Close()
	server = nil
	return os.RemoveAll(directory)
}

//export Java_me_d3sox_nevumi_sftp_TailscaleNative_start
func Java_me_d3sox_nevumi_sftp_TailscaleNative_start(env *C.JNIEnv, receiver C.jobject, directory, hostname, controlURL, authKey C.jstring) {
	if err := start(goString(env, directory), goString(env, hostname), goString(env, controlURL), goString(env, authKey)); err != nil {
		throw(env, err)
	}
}

//export Java_me_d3sox_nevumi_sftp_TailscaleNative_status
func Java_me_d3sox_nevumi_sftp_TailscaleNative_status(env *C.JNIEnv, receiver C.jobject) C.jstring {
	result, err := status()
	if err != nil {
		throw(env, err)
		return 0
	}
	value := C.CString(result)
	defer C.free(unsafe.Pointer(value))
	return C.new_string(env, value)
}

//export Java_me_d3sox_nevumi_sftp_TailscaleNative_signOut
func Java_me_d3sox_nevumi_sftp_TailscaleNative_signOut(env *C.JNIEnv, receiver C.jobject) {
	if err := signOut(); err != nil {
		throw(env, err)
	}
}

//export Java_me_d3sox_nevumi_sftp_TailscaleNative_dial
func Java_me_d3sox_nevumi_sftp_TailscaleNative_dial(env *C.JNIEnv, receiver C.jobject, host C.jstring, port, timeoutMs C.jint) C.jlong {
	handle, err := dial(goString(env, host), int(port), time.Duration(timeoutMs)*time.Millisecond)
	if err != nil {
		throw(env, err)
		return 0
	}
	return C.jlong(handle)
}

//export Java_me_d3sox_nevumi_sftp_TailscaleNative_read
func Java_me_d3sox_nevumi_sftp_TailscaleNative_read(env *C.JNIEnv, receiver C.jobject, handle C.jlong, array C.jbyteArray, offset, length C.jint) C.jint {
	conn, err := connection(handle)
	if err != nil {
		throw(env, err)
		return -1
	}
	buffer := make([]byte, length)
	size, err := conn.Read(buffer)
	if size > 0 {
		C.set_bytes(env, array, offset, C.jint(size), unsafe.Pointer(&buffer[0]))
		return C.jint(size)
	}
	if errors.Is(err, io.EOF) {
		return -1
	}
	if err != nil {
		throw(env, err)
	}
	return 0
}

//export Java_me_d3sox_nevumi_sftp_TailscaleNative_write
func Java_me_d3sox_nevumi_sftp_TailscaleNative_write(env *C.JNIEnv, receiver C.jobject, handle C.jlong, array C.jbyteArray, offset, length C.jint) {
	conn, err := connection(handle)
	if err != nil {
		throw(env, err)
		return
	}
	if length == 0 {
		return
	}
	buffer := make([]byte, length)
	C.get_bytes(env, array, offset, length, unsafe.Pointer(&buffer[0]))
	if _, err := conn.Write(buffer); err != nil {
		throw(env, err)
	}
}

//export Java_me_d3sox_nevumi_sftp_TailscaleNative_close
func Java_me_d3sox_nevumi_sftp_TailscaleNative_close(env *C.JNIEnv, receiver C.jobject, handle C.jlong) {
	if value, ok := connections.LoadAndDelete(int64(handle)); ok {
		value.(net.Conn).Close()
	}
}
