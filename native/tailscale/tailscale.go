// Package main exposes a userspace Tailscale node to Nevumi through JNI.
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
	"tailscale.com/ipn"
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
		closeRelays()
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

// deadlineConn fails reads and writes that stall, like the socket timeout sshj sets on direct connections.
type deadlineConn struct {
	net.Conn
	timeout time.Duration
}

func (c deadlineConn) Read(buffer []byte) (int, error) {
	c.SetReadDeadline(time.Now().Add(c.timeout))
	return c.Conn.Read(buffer)
}

func (c deadlineConn) Write(buffer []byte) (int, error) {
	c.SetWriteDeadline(time.Now().Add(c.timeout))
	return c.Conn.Write(buffer)
}

func dial(host string, port int, timeout, ioTimeout time.Duration) (int64, error) {
	node, err := current()
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	connection, err := dialPeer(ctx, node, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return 0, err
	}
	handle := nextHandle.Add(1)
	connections.Store(handle, deadlineConn{connection, ioTimeout})
	return handle, nil
}

// dialPeer waits for the node, then dials again whenever an attempt times out. The node reports Running with the network map
// cached on disk, which can lack the peer until control sends the current one; a dial in that moment leaves Tailscale and only
// gives up when TCP's retries run out, while a new attempt a few seconds later reaches the peer.
func dialPeer(ctx context.Context, node *tsnet.Server, network, address string) (net.Conn, error) {
	if _, err := node.Up(ctx); err != nil {
		return nil, errNotConnected
	}
	for wait := 2 * time.Second; ; wait *= 2 {
		attempt, cancel := context.WithTimeout(ctx, wait)
		connection, err := node.Dial(attempt, network, address)
		expired := attempt.Err() != nil
		cancel()
		if err == nil || !expired || ctx.Err() != nil {
			return connection, err
		}
	}
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
	client, err := server.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	state, err := client.StatusWithoutPeers(ctx)
	if err != nil {
		return err
	}
	// A node the tailnet knows about keeps its identity until the control server confirms the logout, so the user can retry.
	if registered := state.BackendState == ipn.Running.String() || state.BackendState == ipn.Starting.String() ||
		state.BackendState == ipn.NeedsMachineAuth.String(); registered {
		if err := client.Logout(ctx); err != nil {
			return errors.New("Tailscale could not reach the control server to sign out")
		}
	}
	closeRelays()
	server.Close()
	server = nil
	return os.RemoveAll(directory)
}

//export Java_me_d3sox_nevumi_tunnel_TailscaleNative_start
func Java_me_d3sox_nevumi_tunnel_TailscaleNative_start(env *C.JNIEnv, receiver C.jobject, directory, hostname, controlURL, authKey C.jstring) {
	if err := start(goString(env, directory), goString(env, hostname), goString(env, controlURL), goString(env, authKey)); err != nil {
		throw(env, err)
	}
}

//export Java_me_d3sox_nevumi_tunnel_TailscaleNative_status
func Java_me_d3sox_nevumi_tunnel_TailscaleNative_status(env *C.JNIEnv, receiver C.jobject) C.jstring {
	result, err := status()
	if err != nil {
		throw(env, err)
		return 0
	}
	value := C.CString(result)
	defer C.free(unsafe.Pointer(value))
	return C.new_string(env, value)
}

//export Java_me_d3sox_nevumi_tunnel_TailscaleNative_signOut
func Java_me_d3sox_nevumi_tunnel_TailscaleNative_signOut(env *C.JNIEnv, receiver C.jobject) {
	if err := signOut(); err != nil {
		throw(env, err)
	}
}

//export Java_me_d3sox_nevumi_tunnel_TailscaleNative_relay
func Java_me_d3sox_nevumi_tunnel_TailscaleNative_relay(env *C.JNIEnv, receiver C.jobject, origin C.jstring) C.jstring {
	address, err := relay(goString(env, origin))
	if err != nil {
		throw(env, err)
		return 0
	}
	value := C.CString(address)
	defer C.free(unsafe.Pointer(value))
	return C.new_string(env, value)
}

//export Java_me_d3sox_nevumi_tunnel_TailscaleNative_dial
func Java_me_d3sox_nevumi_tunnel_TailscaleNative_dial(env *C.JNIEnv, receiver C.jobject, host C.jstring, port, timeoutMs, ioTimeoutMs C.jint) C.jlong {
	handle, err := dial(goString(env, host), int(port), time.Duration(timeoutMs)*time.Millisecond,
		time.Duration(ioTimeoutMs)*time.Millisecond)
	if err != nil {
		throw(env, err)
		return 0
	}
	return C.jlong(handle)
}

//export Java_me_d3sox_nevumi_tunnel_TailscaleNative_read
func Java_me_d3sox_nevumi_tunnel_TailscaleNative_read(env *C.JNIEnv, receiver C.jobject, handle C.jlong, array C.jbyteArray, offset, length C.jint) C.jint {
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

//export Java_me_d3sox_nevumi_tunnel_TailscaleNative_write
func Java_me_d3sox_nevumi_tunnel_TailscaleNative_write(env *C.JNIEnv, receiver C.jobject, handle C.jlong, array C.jbyteArray, offset, length C.jint) {
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

//export Java_me_d3sox_nevumi_tunnel_TailscaleNative_close
func Java_me_d3sox_nevumi_tunnel_TailscaleNative_close(env *C.JNIEnv, receiver C.jobject, handle C.jlong) {
	if value, ok := connections.LoadAndDelete(int64(handle)); ok {
		value.(net.Conn).Close()
	}
}
