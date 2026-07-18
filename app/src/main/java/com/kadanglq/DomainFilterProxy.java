package com.kadanglq;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.util.Log;

public class DomainFilterProxy {

    private static final String TAG = "DomainFilterProxy";

    private static DomainFilterProxy instance;

    private final int port;
    private final RuleManager ruleManager;
    private ServerSocket serverSocket;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    private DomainFilterProxy(int port, RuleManager ruleManager) {
        this.port = port;
        this.ruleManager = ruleManager;
    }

    public static synchronized void ensureStarted(android.content.Context context, int port) {
        if (instance != null && instance.running) return;
        RuleManager rm = new RuleManager(context);
        instance = new DomainFilterProxy(port, rm);
        instance.start();
    }

    private void start() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
            running = true;
        } catch (IOException e) {
            Log.e(TAG, "listen failed: " + e.getMessage());
            return;
        }

        pool.execute(() -> {
            ruleManager.refreshRulesBlocking();
        });

        pool.execute(this::acceptLoop);
        Log.i(TAG, "listening on 127.0.0.1:" + port);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                pool.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            byte[] hello = readExact(in, 2);
            if (hello == null || (hello[0] & 0xFF) != 0x05) { closeQuiet(client); return; }
            int nMethods = hello[1] & 0xFF;
            readExact(in, nMethods);
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            byte[] head = readExact(in, 4);
            if (head == null || (head[0] & 0xFF) != 0x05) { closeQuiet(client); return; }
            int cmd = head[1] & 0xFF;
            int atyp = head[3] & 0xFF;

            String targetHost;
            if (atyp == 0x01) {
                byte[] addr = readExact(in, 4);
                if (addr == null) { closeQuiet(client); return; }
                targetHost = (addr[0]&0xFF)+"."+(addr[1]&0xFF)+"."+(addr[2]&0xFF)+"."+(addr[3]&0xFF);
            } else if (atyp == 0x03) {
                int len = in.read();
                if (len < 0) { closeQuiet(client); return; }
                byte[] nameBytes = readExact(in, len);
                if (nameBytes == null) { closeQuiet(client); return; }
                targetHost = new String(nameBytes, StandardCharsets.US_ASCII);
            } else if (atyp == 0x04) {
                byte[] addr = readExact(in, 16);
                if (addr == null) { closeQuiet(client); return; }
                targetHost = java.net.InetAddress.getByAddress(addr).getHostAddress();
            } else {
                closeQuiet(client);
                return;
            }

            byte[] portBytes = readExact(in, 2);
            if (portBytes == null) { closeQuiet(client); return; }
            int targetPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

            if (cmd != 0x01) {
                out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0});
                out.flush();
                closeQuiet(client);
                return;
            }

            boolean blocked = !ruleManager.isExpired() && ruleManager.isBlocked(targetHost);
            if (blocked) {
                Log.d(TAG, "blocked: " + targetHost);
                return;
            }

            Socket upstream;
            try {
                upstream = new Socket();
                upstream.connect(new InetSocketAddress(targetHost, targetPort), 8000);
            } catch (IOException e) {
                closeQuiet(client);
                return;
            }

            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0});
            out.flush();

            relay(client, upstream);
        } catch (IOException e) {
            closeQuiet(client);
        }
    }

    private void relay(Socket a, Socket b) {
        Thread t1 = new Thread(() -> pipe(a, b));
        Thread t2 = new Thread(() -> pipe(b, a));
        t1.start();
        t2.start();
    }

    private void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
        } finally {
            closeQuiet(from);
            closeQuiet(to);
        }
    }

    private byte[] readExact(InputStream in, int n) throws IOException {
        if (n == 0) return new byte[0];
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) return null;
            off += r;
        }
        return buf;
    }

    private void closeQuiet(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }
}
