package azuo.modbusadapter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.IBinder;

import androidx.preference.PreferenceManager;

import com.google.android.material.color.MaterialColors;
import com.hoho.android.usbserial.driver.SerialTimeoutException;
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
        getTheme().applyStyle(R.style.Theme_ModbusAdapter, true);
        makeForeground();
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

        UsbManager usb = Objects.requireNonNull(getSystemService(UsbManager.class));
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
        try {
            port.open(connection);
            port.setParameters(
                Integer.parseInt(preferences.getString("uart_baud_rate", "-1")),
                Integer.parseInt(preferences.getString("uart_data_bits", "-1")),
                Integer.parseInt(preferences.getString("uart_stop_bits", "-1")),
                Integer.parseInt(preferences.getString("uart_parity", "-1"))
            );
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

        uart = new UART(port);
        uart.start();

        gateway = new Gateway(socket);
        gateway.start();

        SERVICE_STARTED = true;
        sendBroadcast(new Intent(ACTION_SERVICE_NOTIFY));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (SERVICE_STARTED)
            SERVICE_STARTED = false;

        if (gateway != null) {
            gateway.close();
            gateway = null;
        }

        if (uart != null) {
            uart.close();
            uart = null;
        }

        sendBroadcast(new Intent(ACTION_SERVICE_NOTIFY));
        super.onDestroy();
    }

    private void makeForeground() {
        NotificationChannel channel = new NotificationChannel(
            "modbus_adatper",
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        );
        //channel.setDescription();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, channel.getId())
            //.setContentTitle(getText(R.string.app_name))
            .setContentText(getText(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(MaterialColors.getColor(this, R.attr.colorPrimary, 0))
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build();
        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    private void stopSelf(String error) {
        //debug(error);
        Intent notify = new Intent(ACTION_SERVICE_NOTIFY);
        notify.putExtra("error", error);
        sendBroadcast(notify);
        stopSelf();
    }

    private UART uart = null;

    private class UART extends Thread {
        private final UsbSerialPort port;
        private boolean stop = false;

        public UART(UsbSerialPort port) {
            this.port = port;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[256];
            while (!stop) {
                int n = 0;
                try {
                    n = port.read(buffer, 0);
                    //debug("UART receive", buffer, 0, n);
                }
                catch (Exception e) {
                    if (!stop) {
                        stop = true;
                        stopSelf(getString(R.string.usb_error, e));
                    }
                }
                if (n >= 4 && CRC16(buffer, n - 2) ==
                              ((buffer[n - 2] & 0xFF) | ((buffer[n - 1] & 0xFF) << 8)))
                    gateway.reply(buffer, 0, n - 2);
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
                    //debug("UART transmit", buffer, 0, buffer.length);
                    port.write(buffer, 500);
                }
                catch (SerialTimeoutException ignored) {
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
        private byte T1, T2;
        private boolean stop = false;

        public synchronized void use(Socket socket) throws IOException {
            release();
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

                while (!stop) {
                    int n;
                    try {
                        n = in.read(buffer);
                        //debug("TCP input", buffer, 0, n);
                    }
                    catch (Exception e) {
                        n = -1;
                    }
                    if (n < 0)
                        break;
                    else if (
                        n >= 8 && buffer[2] == 0 && buffer[3] == 0 &&
                        (((buffer[4] & 0xFF) << 8) | (buffer[5] & 0xFF)) == n - 6
                    ) {
                        T1 = buffer[0];
                        T2 = buffer[1];
                        uart.transmit(buffer, 6, n - 6);
                    }
                }

                synchronized (this) {
                    release();
                    input = null;
                    output = null;
                }
            }
        }

        public void send(byte[] data, int offset, int length) {
            final OutputStream out = output;
            if (out != null && !stop && length >= 2 && length <= 254) {
                byte[] buffer = new byte[length + 6];
                buffer[0] = T1;
                buffer[1] = T2;
                buffer[2] = 0;
                buffer[3] = 0;
                buffer[4] = 0; //(byte)((length >> 8) & 0xFF);
                buffer[5] = (byte)(length & 0xFF);
                System.arraycopy(data, offset, buffer, 6, length);
                try {
                    //debug("TCP output", buffer, 0, buffer.length);
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

    private static void debug(String message) {
        android.util.Log.i("AdaterService", message);
    }

    private static void debug(String prefix, byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder(prefix).append(":\n");
        for (int i = offset; i < offset + length; ++i) {
            String s = Integer.toHexString(data[i] & 0xFF);
            if (s.length() == 1)
                sb.append('0');
            sb.append(s).append(' ');
        }
        android.util.Log.i("AdaterService", sb.toString());
    }
}
