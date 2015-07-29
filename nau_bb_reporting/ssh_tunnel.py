"""
adapted from:
https://github.com/paramiko/paramiko/blob/master/demos/forward.py
http://stackoverflow.com/questions/2777884/shutting-down-ssh-tunnel-in-paramiko-programatically

This adaptation keeps the tunnel alive in a background thread while the reports run.
The paramiko example is a blocking tunnel, not suitable to be built into a tool.

This should ideally be implemented as a class that contains a Handler and SSHClient,
but the scale of this project is small enough that using a couple of globals is fine.
A CLI tool probably shouldn't be expanded to run multiple tunnels in the same process.
If it needs to be, then just turn this into an object with state.
"""
__author__ = 'adam'
import select
import logging
import threading

import paramiko

try:
    import SocketServer
except ImportError:
    import socketserver as SocketServer

log = logging.getLogger("nau_bb_reporting.ssh_tunnel")

tunnel_server = None
ssh_client = None


class ForwardServer(SocketServer.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True


class Handler(SocketServer.BaseRequestHandler):
    def handle(self):
        chan = self.ssh_transport.open_channel('direct-tcpip',
                                               (self.chain_host, self.chain_port),
                                               self.request.getpeername())

        if chan is None:
            log.error("SSH connection refused.")
            return

        log.info("SSH tunnel created.")
        while True:
            r, w, x = select.select([self.request, chan], [], [])
            if self.request in r:
                data = self.request.recv(1024)
                if len(data) == 0:
                    break
                chan.send(data)
            if chan in r:
                data = chan.recv(1024)
                if len(data) == 0:
                    break
                self.request.send(data)

def setup_tunnel(ssh_host, ssh_port, ssh_user, ssh_pass, local_port, remote_host, remote_port):
    global ssh_client
    ssh_client = paramiko.SSHClient()
    ssh_client.load_system_host_keys()
    ssh_client.set_missing_host_key_policy(paramiko.WarningPolicy())

    ssh_client.connect(ssh_host, ssh_port, username=ssh_user, password=ssh_pass)

    class SubHander(Handler):
        chain_host = remote_host
        chain_port = remote_port
        ssh_transport = ssh_client.get_transport()

    global tunnel_server
    tunnel_server = ForwardServer(('', local_port), SubHander)
    tunnel_server.serve_forever()


def start_tunnel(ssh_host, ssh_port, ssh_user, ssh_pass, local_port, remote_host, remote_port):
    thread = threading.Thread(
        target=setup_tunnel, args=(ssh_host, ssh_port, ssh_user, ssh_pass, local_port, remote_host, remote_port))
    thread.daemon = True
    thread.start()


def stop_tunnel():
    if tunnel_server is not None:
        tunnel_server.shutdown()


def tunnel_active():
    if ssh_client is None:
        return False
    elif ssh_client.get_transport() is None:
        return False
    else:
        return ssh_client.get_transport().is_active()
