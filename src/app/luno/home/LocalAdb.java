package app.luno.home;

import android.content.Context;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;

/**
 * A minimal ADB client for the TV's own adbd (network debugging, 127.0.0.1:5555). Shell commands
 * run as the shell user, which may enable and disable apps; an installed app can't do that itself.
 * The first connection makes the TV ask "Allow network debugging?" for this launcher's key: tick
 * "Always allow". The key lives in the launcher's private files. Also installs Luno's own updates.
 */
final class LocalAdb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** DER prefix of a SHA-1 DigestInfo: adbd's token is signed as if it were a SHA-1 hash. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03,
            0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private LocalAdb() { }

    /** Runs a shell command and returns its output. Blocks; call off the main thread. */
    static String shell(Context ctx, String command) throws Exception {
        try (Conn c = connect(ctx, true)) {
            c.open("shell:" + command);
            return c.readToClose();
        }
    }

    /**
     * Installs an APK as the shell user, with no prompt (streams it to "cmd package install").
     * Only over a connection the TV already trusts: never asks "Allow network debugging?", since
     * this runs in the background. Returns pm's output ("Success" when it worked).
     */
    static String install(Context ctx, File apk) throws Exception {
        try (Conn c = connect(ctx, false); InputStream data = new FileInputStream(apk)) {
            c.open("exec:cmd package install -S " + apk.length());
            byte[] buf = new byte[Math.min(c.maxData, 64 * 1024)];
            StringBuilder output = new StringBuilder();
            for (int n; (n = data.read(buf)) > 0; ) {
                send(c.out, WRTE, 1, c.remote, Arrays.copyOf(buf, n));
                while (true) { // each write waits for its OKAY; pm may write meanwhile
                    Msg m = read(c.in);
                    if (m.cmd == OKAY) break;
                    if (m.cmd == CLSE) return output.toString();
                    if (m.cmd == WRTE) c.ack(m, output);
                }
            }
            return output.append(c.readToClose()).toString();
        }
    }

    /** The TV doesn't trust Luno's key yet (and wasn't asked to). */
    static final class NotTrusted extends IOException { }

    /** An authenticated connection to the TV's adbd, with at most one stream open. */
    private static final class Conn implements AutoCloseable {
        final Socket socket = new Socket();
        DataInputStream in;
        OutputStream out;
        int maxData = 4096, remote;

        void open(String service) throws IOException {
            send(out, OPEN, 1, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
            while (true) {
                Msg m = read(in);
                if (m.cmd == OKAY) { remote = m.arg0; return; }
                if (m.cmd == CLSE) throw new IOException("adb refused " + service);
            }
        }

        void ack(Msg m, StringBuilder output) throws IOException {
            output.append(new String(m.data, StandardCharsets.UTF_8));
            send(out, OKAY, 1, m.arg0, new byte[0]);
        }

        String readToClose() throws IOException {
            StringBuilder output = new StringBuilder();
            while (true) {
                Msg m = read(in);
                if (m.cmd == WRTE) ack(m, output);
                else if (m.cmd == CLSE) return output.toString();
            }
        }

        @Override public void close() throws IOException { socket.close(); }
    }

    /** Connects and authenticates; without allowPrompt, fails rather than make the TV ask. */
    private static Conn connect(Context ctx, boolean allowPrompt) throws Exception {
        KeyPair key = key(ctx);
        Conn c = new Conn();
        try {
            c.socket.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            c.socket.setSoTimeout(60_000); // long enough to accept the TV's prompt the first time
            c.in = new DataInputStream(c.socket.getInputStream());
            c.out = c.socket.getOutputStream();

            send(c.out, CNXN, 0x01000000, 4096, "host::luno\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false;
            while (true) {
                Msg m = read(c.in);
                if (m.cmd == CNXN) { c.maxData = m.arg1; return c; }
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) continue;
                if (!signed) {
                    send(c.out, AUTH, AUTH_SIGNATURE, 0, sign(key, m.data));
                    signed = true;
                } else if (allowPrompt) {
                    send(c.out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey(key));
                } else {
                    throw new NotTrusted();
                }
            }
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        m.data = new byte[h.getInt()];
        in.readFully(m.data);
        return m;
    }

    private static byte[] sign(KeyPair key, byte[] token) throws Exception {
        byte[] digestInfo = new byte[SHA1_PREFIX.length + token.length];
        System.arraycopy(SHA1_PREFIX, 0, digestInfo, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, digestInfo, SHA1_PREFIX.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key.getPrivate()); // private-key "encrypt" = PKCS#1 v1.5 signature
        return c.doFinal(digestInfo);
    }

    /** The public key in adbd's format: base64 of its RSAPublicKey struct, then a name. */
    private static byte[] publicKey(KeyPair key) {
        RSAPublicKey pub = (RSAPublicKey) key.getPublic();
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue()).put(littleEndian(n)).put(littleEndian(rr))
                .putInt(pub.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " luno@launcher\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger v) {
        byte[] be = v.toByteArray(), le = new byte[256];
        for (int i = 0; i < 256 && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }

    private static synchronized KeyPair key(Context ctx) throws Exception {
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        Files.write(priv.toPath(), k.getPrivate().getEncoded());
        Files.write(pub.toPath(), k.getPublic().getEncoded());
        return k;
    }
}
