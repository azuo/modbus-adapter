package azuo.modbusadapter;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.color.MaterialColors;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Objects;

public class AdapterService extends Service {
    private static final int ONGOING_NOTIFICATION_ID = AdapterService.class.hashCode();

    public static final String ACTION_USB_PERMISSION = "azuo.modbusadatper.USB_PERMISSION";
    public static final String ACTION_SERVICE_NOTIFY = "azuo.modbusadatper.SERVICE_NOTIFY";
    public static boolean SERVICE_STARTED = false;

    public AdapterService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        getTheme().applyStyle(R.style.Theme_ModbusAdapter, true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        makeForeground();
        if (SERVICE_STARTED)
            return START_STICKY;

        UsbManager usb = Objects.requireNonNull((UsbManager)getSystemService(USB_SERVICE));
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb);
        if (drivers.isEmpty()) {
            stopSelf(getString(R.string.no_usb));
            return START_NOT_STICKY;
        }

        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();
        if (!usb.hasPermission(device)) {
            usb.requestPermission(
                device,
                PendingIntent.getBroadcast(
                    this,
                    0,
                    new Intent(ACTION_USB_PERMISSION),
                    0 // must be mutable, otherwise EXTRA_PERMISSION_GRANTED never true?
                )
            );
            stopSelf();
            return START_NOT_STICKY;
        }

        UsbDeviceConnection connection = usb.openDevice(device);
        if (connection == null) {
            stopSelf(getString(R.string.open_usb_failed, "openDevice failed."));
            return START_NOT_STICKY;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        UsbSerialPort port = driver.getPorts().get(0);
        int baudRate, charBits;
        try {
            port.open(connection);
            baudRate =
                Integer.parseInt(preferences.getString("uart_baud_rate", "-1"));
            int dataBits =
                Integer.parseInt(preferences.getString("uart_data_bits", "-1"));
            int stopBits =
                Integer.parseInt(preferences.getString("uart_stop_bits", "-1"));
            int parity =
                Integer.parseInt(preferences.getString("uart_parity", "-1"));
            port.setParameters(baudRate, dataBits, stopBits, parity);
            charBits = dataBits + (stopBits > 2 ? 2 : stopBits) + (parity > 0 ? 1 : 0);
        }
        catch (Exception e) {
            if (port.isOpen()) {
                try {
                    port.close();   // will also close connection
                }
                catch (Exception ignored) {
                }
            }
            else
                connection.close();
            stopSelf(getString(R.string.open_usb_failed, e));
            return START_NOT_STICKY;
        }

        ServerSocket socket;
        try {
            socket = new ServerSocket(
                Integer.parseInt(preferences.getString("tcp_port", "-1")),
                1
            );
        }
        catch (Exception e) {
            try {
                port.close();
            }
            catch (Exception ignored) {
            }
            stopSelf(getString(R.string.tcp_error, e));
            return START_NOT_STICKY;
        }

        uart = new UART(port, baudRate, charBits);
        uart.start();

        gateway = new Gateway(socket);
        gateway.start();

        SERVICE_STARTED = true;
        sendBroadcast(new Intent(ACTION_SERVICE_NOTIFY));
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!SERVICE_STARTED)
            stopSelf();
    }

    @Override
    public void onDestroy() {
        if (gateway != null) {
            gateway.close();
            gateway = null;
        }

        if (uart != null) {
            uart.close();
            uart = null;
        }

        SERVICE_STARTED = false;
        sendBroadcast(new Intent(ACTION_SERVICE_NOTIFY));
        super.onDestroy();
    }

    private void makeForeground() {
        NotificationChannelCompat channel = new NotificationChannelCompat.Builder(
            "modbus_adatper",
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        ).setName(getString(R.string.channel_name)).build();
        NotificationManagerCompat.from(this).createNotificationChannel(channel);

        Notification notification = new NotificationCompat.Builder(this, channel.getId())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentTitle(getText(R.string.notification_title))
            .setContentText(getText(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(MaterialColors.getColor(this, R.attr.colorPrimary, 0))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    new Intent(this, MainActivity.class),
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0
                )
            ).build();
        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    private void stopSelf(String error) {
        debug(error);
        Intent notify = new Intent(ACTION_SERVICE_NOTIFY);
        notify.putExtra("error", error);
        sendBroadcast(notify);
        stopSelf();
    }

    private UART uart = null;

    private class UART extends Thread {
        private final UsbSerialPort port;
        private final int baudRate;
        private final int charBits;
        private boolean stop = false;

        public UART(UsbSerialPort port, int baudRate, int charBits) {
            this.port = port;
            this.baudRate = baudRate;
            this.charBits = charBits;
        }

        @Override
        public void run() {
            if (debugEnabled())
                debug(port.getDevice().toString());

            final int packetSize = port.getReadEndpoint().getMaxPacketSize();
            final byte[] buffer = new byte[256];
            nextRead:
            for (int n = 0, expected = 4; !stop; ) {
                try {
                    if (n <= 0) {
                        n = port.read(buffer, 0);
                        if (stop || n <= 0)
                            continue;
                        expected = 4;
                    }

                    while (n < expected) {
                        // buggy bulkTransfer (used by port.read if timeout is not 0) fails if more
                        // than packetSize bytes data is read?
                        byte[] more = new byte[Math.min(packetSize, buffer.length - n)];

                        // minimum number of characters to be read
                        int size = Math.min(more.length, expected - n);

                        // if baudRate > 19200
                        //   inter-frame delay = 1.75ms
                        //   inter-character delay = 0.75ms
                        // else
                        //   inter-frame delay = 3.5 * character time
                        //   inter-character delay = 1.5 * character time
                        int timeout = baudRate > 19200 ?
                            size * charBits * 1000 / baudRate + 1 +
                            size * 3 / 4 + 2		// ceil((size - 1) * 0.75 + 1.75)
                            :
                            (size * 5 / 2 + 2) *	// size + (size - 1) * 1.5 + 3.5
                            charBits * 1000 / baudRate + 1;

                        int read = port.read(more, timeout);
                        if (stop || read <= 0) {
                            if (!stop && debugEnabled())
                                debug(
                                    "RTU read " + size + " more bytes timed out after " +
                                        timeout + "ms",
                                    buffer, 0, n
                                );
                            n = 0;
                            continue nextRead;
                        }
                        System.arraycopy(more, 0, buffer, n, read);
                        n += read;
                    }
                    //debug("RTU read " + n + " of " + expected, buffer, 0, n);
                }
                catch (Exception e) {
                    if (!stop) {
                        stop = true;
                        stopSelf(getString(R.string.usb_error, e));
                    }
                    break;
                }

                if (expected == 4) {
                    if ((buffer[1] & 0x80) != 0)
                        expected = 5;
                    else if (buffer[1] == 0x01 || buffer[1] == 0x02 ||
                             buffer[1] == 0x03 || buffer[1] == 0x04)
                        expected = 5 + (buffer[2] & 0xFF);
                    else if (buffer[1] == 0x05 || buffer[1] == 0x06 ||
                             buffer[1] == 0x0F || buffer[1] == 0x10)
                        expected = 8;
                    else
                        expected = n;

                    if (buffer[1] == 0 || expected > buffer.length) {
                        debug("RTU invalid packet", buffer, 0, n);
                        n = 0;
                        continue;
                    }
                }

                if (n >= expected) {
                    if (CRC16(buffer, expected - 2) ==
                        ((buffer[expected - 2] & 0xFF) | ((buffer[expected - 1] & 0xFF) << 8)))
                        gateway.reply(buffer, 0, expected - 2);
                    else
                        debug("RTU bad CRC", buffer, 0, expected);

                    n -= expected;
                    if (n > 0)
                        System.arraycopy(buffer, expected, buffer, 0, n);
                    expected = 4;
                }
            }
        }

        public void transmit(byte[] data, int offset, int length) {
            if (!stop && length >= 2 && length <= 254) {
                byte[] buffer = new byte[length + 2];
                System.arraycopy(data, offset, buffer, 0, length);
                int crc = CRC16(buffer, length);
                buffer[length] = (byte)(crc & 0xFF);
                buffer[length + 1] = (byte)((crc >> 8) & 0xFF);
                try {
                    //debug("RTU write", buffer, 0, buffer.length);
                    port.write(buffer, 0);
                }
                catch (Exception e) {
                    if (!stop)
                        stopSelf(getString(R.string.usb_error, e));
                }
            }
        }

        public void close() {
            stop = true;
            if (port.isOpen()) {
                try {
                    port.close();
                }
                catch (Exception ignored) {
                }
            }
            if (isAlive()) {
                try {
                    join();
                }
                catch (Exception ignored) {
                }
            }
        }

        private int CRC16(byte[] data, int length) {
            int crc = 0xFFFF;
            for (int i = 0; i < length; i ++) {
                crc ^= (data[i] & 0xFF);
                for (int j = 0; j < 8; j ++) {
                    if ((crc & 1) != 0) {
                        crc >>= 1;
                        crc ^= 0xA001;
                    }
                    else {
                        crc >>= 1;
                    }
                }
            }
            return crc;
        }
    }

    private Gateway gateway = null;

    private class Gateway extends Thread {
        private final ServerSocket socket;
        private TCP tcp = null;
        private boolean stop = false;

        public Gateway(ServerSocket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            tcp = new TCP();
            tcp.start();

            while (!stop) {
                Socket client = null;
                try {
                    client = socket.accept();
                }
                catch (Exception e) {
                    if (!stop) {
                        stop = true;
                        stopSelf(getString(R.string.tcp_error, e));
                    }
                }
                if (client != null) {
                    //debug("Accepted connection.");
                    try {
                        tcp.use(client);
                    }
                    catch (Exception e) {
                        try {
                            client.close();
                        }
                        catch (Exception ignored) {
                        }
                    }
                }
            }

            tcp.close();
            tcp = null;
        }

        public void reply(byte[] data, int offset, int length) {
            if (!stop && tcp != null)
                tcp.send(data, offset, length);
        }

        public void close() {
            stop = true;
            if (!socket.isClosed()) {
                try {
                    socket.close();
                }
                catch (Exception ignored) {
                }
            }
            if (isAlive()) {
                try {
                    join();
                }
                catch (Exception ignored) {
                }
            }
        }
    }

    private class TCP extends Thread {
        private InputStream input = null;
        private OutputStream output = null;
        private byte T1, T2, UI, FC;
        private boolean stop = false;

        public synchronized void use(Socket socket) throws IOException {
            release();
            //socket.setSoTimeout(60000);
            input = socket.getInputStream();
            output = socket.getOutputStream();
            notify();
        }

        @Override
        public void run() {
            final byte[] buffer = new byte[260];
            while (!stop) {
                InputStream in;
                synchronized (this) {
                    while (!stop && (input == null || output == null)) {
                        try {
                            wait();
                        }
                        catch (InterruptedException e) {
                            stop = true;
                        }
                    }
                    in = input;
                }

                nextRead:
                for (int n = 0, expected = 6; !stop; ) {
                    while (n < expected) {
                        int read;
                        try {
                            read = in.read(buffer, n, buffer.length - n);
                        }
                        catch (Exception e) {
                            read = -1;
                        }
                        if (stop || read < 0)
                            break nextRead;
                        n += read;
                    }
                    //debug("TCP read " + n + " of " + expected, buffer, 0, n);

                    if (expected == 6) {
                        expected += (((buffer[4] & 0xFF) << 8) | (buffer[5] & 0xFF));
                        if (buffer[2] != 0 || buffer[3] != 0 ||
                            expected < 8 || expected > buffer.length) {
                            debug("TCP invalid packet", buffer, 0, n);
                            n = 0;
                            expected = 6;
                            continue;
                        }
                    }

                    if (n >= expected) {
                        T1 = buffer[0];
                        T2 = buffer[1];
                        UI = buffer[6];
                        FC = buffer[7];
                        uart.transmit(buffer, 6, expected - 6);

                        n -= expected;
                        if (n > 0)
                            System.arraycopy(buffer, expected, buffer, 0, n);
                        expected = 6;
                    }
                }

                synchronized (this) {
                    if (in == input) {
                        release();
                        input = null;
                        output = null;
                    }
                }
            }
        }

        public void send(byte[] data, int offset, int length) {
            final OutputStream out = output;
            if (out != null && !stop && length >= 2 && length <= 254) {
                if (data[offset] != UI || (data[offset + 1] & 0x7F) != FC) {
                    if (debugEnabled())
                        debug(String.format(
                            "Mismatched reply to query %02x %02x", UI & 0xFF, FC & 0xFF
                        ), data, offset, length);
                    return;
                }
                byte[] buffer = new byte[length + 6];
                buffer[0] = T1;
                buffer[1] = T2;
                buffer[2] = 0;
                buffer[3] = 0;
                buffer[4] = 0; //(byte)((length >> 8) & 0xFF);
                buffer[5] = (byte)(length & 0xFF);
                System.arraycopy(data, offset, buffer, 6, length);
                try {
                    //debug("TCP write", buffer, 0, buffer.length);
                    out.write(buffer);
                }
                catch (Exception ignored) {
                }
            }
        }

        private void release() {
            if (input != null) {
                try {
                    input.close();
                }
                catch (Exception ignored) {
                }
            }
            if (output != null) {
                try {
                    output.close();
                }
                catch (Exception ignored) {
                }
            }
        }

        public void close() {
            stop = true;
            synchronized (this) {
                release();
                input = null;
                output = null;
                notify();
            }
            if (isAlive()) {
                try {
                    join();
                }
                catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean debugEnabled() {
        return App.DEBUG_LOG != null;
    }

    private static void debug(String message) {
        if (debugEnabled())
            android.util.Log.i("AdapterService", message);
    }

    private static void debug(String prefix, byte[] data, int offset, int length) {
        if (debugEnabled()) {
            StringBuilder sb = new StringBuilder(prefix).append(":\n");
            for (int i = offset; i < offset + length; ++i) {
                String s = Integer.toHexString(data[i] & 0xFF);
                if (s.length() == 1)
                    sb.append('0');
                sb.append(s).append(' ');
            }
            android.util.Log.i("AdapterService", sb.toString());
        }
    }
}
