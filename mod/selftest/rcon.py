"""极简 Minecraft RCON 客户端（回归脚手架专用，仅标准库）。"""
import socket
import struct

HOST = "127.0.0.1"
PORT = 25576
PASSWORD = "nebula"


def _recv(sock):
    head = b""
    while len(head) < 4:
        chunk = sock.recv(4 - len(head))
        if not chunk:
            raise ConnectionError("RCON 连接关闭")
        head += chunk
    length = struct.unpack("<i", head)[0]
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    req_id, _ = struct.unpack("<ii", data[:8])
    return req_id, data[8:-2].decode("utf-8", "replace")


def _send(sock, req_id, kind, payload):
    body = struct.pack("<ii", req_id, kind) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)


def run(command, timeout=10):
    """执行一条控制台命令并返回服务端响应文本。"""
    sock = socket.create_connection((HOST, PORT), timeout=timeout)
    try:
        _send(sock, 1, 3, PASSWORD)
        req_id, _ = _recv(sock)
        if req_id == -1:
            raise RuntimeError("RCON 认证失败")
        _send(sock, 2, 2, command)
        parts = []
        sock.settimeout(1.0)
        for _ in range(2):
            try:
                _, payload = _recv(sock)
                if payload:
                    parts.append(payload)
            except (socket.timeout, ConnectionError):
                break
        return "\n".join(parts)
    finally:
        sock.close()
