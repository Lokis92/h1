import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceHandle;
import org.usb4java.DeviceList;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;

public class Co2mon {
	private static final short VendorID = (short) 0x04D9;
	private static final short ProductID = (short) 0xA052;
	private static final short InterfaceID = (short)0x0000;
	private static final short TempCode = (short)0x42;
	private static final short Co2Code = (short)0x50;
	
	private static final byte[] magic_table = new byte[8];
	private static final byte[] magic_word = "Htemp99e".getBytes();
	
	
	private static final boolean doOut = true;
	private static final boolean doErr = true;

	private static String proxyHost = "ws-proxy";
	private static int proxyPort = 8080;
	
	private final Device device;
	private final DeviceHandle handle;
	private final byte[] magic_word_wrapped = new byte[magic_word.length];
	
	private double co2 = -1;
	private double temp = -1;
	
	private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
	private static final String workTimeStart = "07:00:00";
	private static final String workTimeEnd = "23:59:59";
	
	private Co2mon() throws LibUsbException {
		Context context = new Context();
		int result = LibUsb.init(context);
		if (result != LibUsb.SUCCESS) 
			throw new LibUsbException("Unable to initialize libusb.", result);
		this.device = findDevice(VendorID, ProductID);
		if (this.device == null)
			throw new LibUsbException("No detector found", LibUsb.ERROR_NO_DEVICE);
		
		handle = new DeviceHandle();
		result = LibUsb.open(device, handle);
		if (result != LibUsb.SUCCESS) 
			throw new LibUsbException("Unable to open USB device", result);
		
		result = LibUsb.claimInterface(handle, InterfaceID);
		if (result != LibUsb.SUCCESS) 
			throw new LibUsbException("Unable to claim interface ", result);
		
		boolean detach = LibUsb.hasCapability(LibUsb.CAP_SUPPORTS_DETACH_KERNEL_DRIVER) && LibUsb.kernelDriverActive(handle, InterfaceID) == 0;
		if (detach) {
			result = LibUsb.detachKernelDriver(handle, InterfaceID);
		    if (result != LibUsb.SUCCESS) 
		    	throw new LibUsbException("Unable to detach kernel driver", result);
		}
		
		ByteBuffer bb = ByteBuffer.allocateDirect(8);
		bb.put(magic_table, 0, 8);
		result = LibUsb.controlTransfer(handle, 
	    		 						(byte)(LibUsb.ENDPOINT_OUT | LibUsb.REQUEST_TYPE_CLASS | LibUsb.RECIPIENT_INTERFACE), 
	    		 						LibUsb.REQUEST_SET_CONFIGURATION, 
	    		 						(short)0x0300, (short)0, 
	    		 						bb, 2000);
		if (result < 0 && result != 8) 
			throw new LibUsbException("Unable to send magic table", result);
		
		for (int i = 0; i < magic_word.length; i++)
			this.magic_word_wrapped[i] = (byte)((magic_word[i] << 4) | (magic_word[i] >> 4));
	}
	
	private Device findDevice(short vendorId, short productId) throws LibUsbException {
	    DeviceList list = new DeviceList();
	    int result = LibUsb.getDeviceList(null, list);
	    if (result < 0) 
	    	throw new LibUsbException("Unable to get device list", result);
	    try {
	        for (Device device: list) {
	            DeviceDescriptor descriptor = new DeviceDescriptor();
	            result = LibUsb.getDeviceDescriptor(device, descriptor);
	            if (result != LibUsb.SUCCESS)
	            	continue;
	            	//throw new LibUsbException("Unable to read device descriptor", result);
	            if (descriptor.idVendor() == vendorId && descriptor.idProduct() == productId) 
	            	return device;
	        }
	    } finally {
	        LibUsb.freeDeviceList(list, false);
	    }
	    return null;
	}
	
	public void runOnce() {
	    byte[] bb = new byte[8];
	    byte[] decoded = new byte[8];
	    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(8);
		IntBuffer intBuffer = IntBuffer.allocate(1);

		double co2 = -1;
		double temp = -1;
		while (co2 < 0 || temp < 0) {
			byteBuffer.clear();
			intBuffer.clear();
		    int result = LibUsb.interruptTransfer(handle, 
		    							      (byte)(LibUsb.ENDPOINT_IN | LibUsb.REQUEST_TYPE_STANDARD | LibUsb.RECIPIENT_INTERFACE), 
		    							      byteBuffer, intBuffer, 5000);
		    if (result != LibUsb.SUCCESS)
		    	throw new LibUsbException("Reading data", result);
		    int size = intBuffer.get();
		    if (size != 8) {
		    	System.err.println("Read " + size + ": ignore");
		    	continue;
		    }
		    byteBuffer.get(bb, 0, 8);
		    decode_buf(bb, decoded, magic_table);
		    if (decoded[4] != 0x0d) {
		    	System.err.println("Invalid r[4]: " + decoded[4]);
		    	continue;
		    }
		    byte checksum = (byte)(decoded[0] + decoded[1] + decoded[2]);
		    if (checksum != decoded[3]) {
		    	System.err.println("Invalid checksum: " + decoded[3] + " vs. " + checksum);
		    	continue;
		    }
		    int r1 = (decoded[1] + 256) % 256;
		    int r2 = (decoded[2] + 256) % 256;
		    long val = (r1 << 8) + r2;
		    if (decoded[0] == TempCode)
		    	temp = val * 0.0625 - 273.15;
		    else if (decoded[0] == Co2Code)
		    	co2 = val;
		}
		this.co2 = co2;
		this.temp = temp;
	}
	
	public double getTemp() {
		return temp;
	}
	
	public double getCO2() {
		return co2;
	}
	
	public void close() {
	    LibUsb.close(handle);
	}
	
	
    private void swap_char(byte[] buf, int idx1, int idx2) {
    	byte b = buf[idx1];
    	buf[idx1] = buf[idx2];
    	buf[idx2] = b;
    }
    
    private byte shift(byte b1, byte b2) {
    	byte v1  = (byte)(b1 << 5);
    	v1 &= 0xe0;
    	byte v2 = (byte)(b2 >> 3);
    	v2 &= 0x1f;
    	return (byte)(v1 | v2);
    }
    
    private void decode_buf(byte[] buf, byte[] result, byte[] magic_table) {
        swap_char(buf, 0, 2);
        swap_char(buf, 1, 4);
        swap_char(buf, 3, 7);
        swap_char(buf, 5, 6);

        for (int i = 0; i < 8; ++i) 
        	buf[i] ^= magic_table[i];
        
        result[7] = shift(buf[6], buf[7]);
        result[6] = shift(buf[5], buf[6]);
        result[5] = shift(buf[4], buf[5]);
        result[4] = shift(buf[3], buf[4]);
        result[3] = shift(buf[2], buf[3]);
        result[2] = shift(buf[1], buf[2]);
        result[1] = shift(buf[0], buf[1]);
        result[0] = shift(buf[7], buf[0]);
        for (int i = 0; i < 8; i++) 
        	result[i] -= magic_word_wrapped[i];
    }

	public static void main(String[] args) throws Exception {
		String apiKey = "ZFLBRJVI97N1RLHV";
		Co2mon co2mon = new Co2mon();
		boolean forever = false;
		long sleepTime = 5 * 60;
		for (String arg: args) {
			forever |= "-a".equalsIgnoreCase(arg);
			try {
				sleepTime = Integer.parseInt(arg);
			} catch (Exception e) {}
		}
		if (forever && sleepTime > 0)
			sleepTime *= 1000;
		else
			sleepTime = -1;
		PrintStream psOut = new PrintStream(new FileOutputStream("co2.out", true), true); // append & auto flush
		PrintStream psErr = new PrintStream(new FileOutputStream("co2.err", true), true); // append & auto flush
		SimpleDateFormat sdfDate = new SimpleDateFormat("MM.dd HH:mm:ss");
		DecimalFormat df = new DecimalFormat("#0.000");
		DecimalFormatSymbols dfs = new DecimalFormatSymbols();
		dfs.setDecimalSeparator('.');
		df.setDecimalFormatSymbols(dfs);
		do { 
			try {
				String now = sdf.format(new Date());
				if (now.compareTo(workTimeStart) > 0 && now.compareTo(workTimeEnd) < 0) {
					if (co2mon == null)
						co2mon = new Co2mon();
					co2mon.runOnce();
					StringBuilder sb = new StringBuilder("https://api.thingspeak.com/update?api_key=").append(apiKey);
					sb.append("&field1=").append(df.format(co2mon.getTemp()));
					sb.append("&field2=").append(df.format(co2mon.getCO2()));
					Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
					URL url = new URL(sb.toString());
					InputStream is = url.openConnection(proxy).getInputStream();
					try {
						if (doOut) {
							psOut.print(sdfDate.format(new Date()) + " ");
							BufferedReader br = new BufferedReader(new InputStreamReader(is));
							while (true) {
								String s = br.readLine();
								if (s == null)
									break;
								psOut.println(s);
								System.out.println(s);
							}
							br.close();
						} else
							is.close();
					} catch (Exception e) {}
				}
				if (sleepTime > 0)
					Thread.sleep(sleepTime);
			} catch (Throwable t) {
				if (doErr) {
					psErr.println(sdfDate.format(new Date()) + " " + t.toString());
					System.err.println(t.toString());
				}
				if (co2mon != null) 
					try {
						co2mon.close();
					} catch (Throwable t2) {
					} finally {
						co2mon = null;
					}
			}
		} while (forever);
		if (co2mon != null)
			co2mon.close();
		psOut.close();
		psErr.close();
	}
	

}
