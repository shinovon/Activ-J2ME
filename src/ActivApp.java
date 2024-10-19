import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Item;
import javax.microedition.lcdui.ItemCommandListener;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

import cc.nnproject.json.JSONObject;

public class ActivApp extends MIDlet implements CommandListener, ItemCommandListener {
	
	private static final String APIURL = "https://activ.kz/";
	
	private static Command exitCmd = new Command("Exit", Command.EXIT, 1);
	
	private static Command sendSmsCmd = new Command("Send SMS", Command.OK, 2);

	private static boolean started;
	
	// ui
	private static Form loginForm;
	
	private static int loginState;

	private static String accessToken;
	private static String refreshToken;

	private static String msisdn;

	private TextField phoneField;

	public ActivApp() {
	}

	protected void destroyApp(boolean unconditional) {

	}

	protected void pauseApp() {

	}

	protected void startApp() {
		if (started) return;
		started = true;
		
		loginForm = new Form("Login");
		loginForm.setCommandListener(this);
		loginForm.addCommand(exitCmd);
		
		phoneField = new TextField("Phone number", "", TextField.PHONENUMBER, 30);
		loginForm.append(phoneField);
	}

	public void commandAction(Command c, Displayable d) {
		if (c == exitCmd) {
			notifyDestroyed();
		}
	}

	public void commandAction(Command c, Item item) {
		
	}
	
	private static JSONObject api(String url) throws IOException {
		JSONObject res;

		HttpConnection hc = null;
		InputStream in = null;
		try {
			hc = open(proxyUrl(APIURL.concat(url)));
			hc.setRequestMethod("GET");
			int c;
			if ((c = hc.getResponseCode()) >= 400) {
				throw new IOException("HTTP ".concat(Integer.toString(c)));
			}
			res = JSONObject.parseObject(readUtf(in = hc.openInputStream(), (int) hc.getLength()));
		} finally {
			if (in != null) try {
				in.close();
			} catch (IOException e) {}
			if (hc != null) try {
				hc.close();
			} catch (IOException e) {}
		}
		System.out.println(res);
		return res;
	}
	
	private static JSONObject apiPost(String url, byte[] body, String type) throws IOException {
		JSONObject res;

		HttpConnection hc = null;
		InputStream in = null;
		try {
			hc = open(proxyUrl(url.startsWith("http") ? url : APIURL.concat(url)));
			hc.setRequestMethod("POST");
			hc.setRequestProperty("Content-length", body == null ? "0" : Integer.toString(body.length));
			if (type != null) hc.setRequestProperty("Content-Type", type);
			if (body != null) {
				OutputStream out = hc.openOutputStream();
				out.write(body);
				out.flush();
				out.close();
			}

			int c;
			if ((c = hc.getResponseCode()) >= 400) {
				throw new IOException("HTTP ".concat(Integer.toString(c)));
			}
			res = JSONObject.parseObject(readUtf(in = hc.openInputStream(), (int) hc.getLength()));
		} finally {
			if (in != null) try {
				in.close();
			} catch (IOException e) {}
			if (hc != null) try {
				hc.close();
			} catch (IOException e) {}
		}
		System.out.println(res);
		return res;
	}
	
	private static String proxyUrl(String url) {
		System.out.println(url);
//		if (url == null
//				|| (!useProxy && (url.indexOf(";tw=") == -1 || !onlineResize))
//				|| proxyUrl == null || proxyUrl.length() == 0 || "https://".equals(proxyUrl)) {
			return url;
//		}
//		return proxyUrl + url(url);
	}
	
	private static byte[] readBytes(InputStream inputStream, int initialSize, int bufferSize, int expandSize)
			throws IOException {
		if (initialSize <= 0) initialSize = bufferSize;
		byte[] buf = new byte[initialSize];
		int count = 0;
		byte[] readBuf = new byte[bufferSize];
		int readLen;
		while ((readLen = inputStream.read(readBuf)) != -1) {
			if (count + readLen > buf.length) {
				System.arraycopy(buf, 0, buf = new byte[count + expandSize], 0, count);
			}
			System.arraycopy(readBuf, 0, buf, count, readLen);
			count += readLen;
		}
		if (buf.length == count) {
			return buf;
		}
		byte[] res = new byte[count];
		System.arraycopy(buf, 0, res, 0, count);
		return res;
	}
	
	private static String readUtf(InputStream in, int i) throws IOException {
		byte[] buf = new byte[i <= 0 ? 1024 : i];
		i = 0;
		int j;
		while ((j = in.read(buf, i, buf.length - i)) != -1) {
			if ((i += j) >= buf.length) {
				System.arraycopy(buf, 0, buf = new byte[i + 2048], 0, i);
			}
		}
		return new String(buf, 0, i, "UTF-8");
	}
	
	private static byte[] get(String url) throws IOException {
		HttpConnection hc = null;
		InputStream in = null;
		try {
			hc = open(url);
			hc.setRequestMethod("GET");
			int r;
			if ((r = hc.getResponseCode()) >= 400) {
				throw new IOException("HTTP ".concat(Integer.toString(r)));
			}
			in = hc.openInputStream();
			return readBytes(in, (int) hc.getLength(), 8*1024, 16*1024);
		} finally {
			try {
				if (in != null) in.close();
			} catch (IOException e) {}
			try {
				if (hc != null) hc.close();
			} catch (IOException e) {}
		}
	}
	
	private static HttpConnection open(String url) throws IOException {
		HttpConnection hc = (HttpConnection) Connector.open(url);
		StringBuffer sb = new StringBuffer("platform=web; brand=activ; locale=en; NEXT_LOCALE=en");
		if (msisdn != null) {
			sb.append("; phonenumber=").append(msisdn);
		}
		hc.setRequestProperty("Cookie", sb.toString());
		hc.setRequestProperty("X-Platform", "WEB");
		hc.setRequestProperty("X-Current-Customer-MSISDN", msisdn);
		hc.setRequestProperty("Authorization", accessToken == null ? "Basic V0VCOg==" : "Bearer ".concat(accessToken));
		return hc;
	}
	
	public static String url(String url) {
		StringBuffer sb = new StringBuffer();
		char[] chars = url.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			int c = chars[i];
			if (65 <= c && c <= 90) {
				sb.append((char) c);
			} else if (97 <= c && c <= 122) {
				sb.append((char) c);
			} else if (48 <= c && c <= 57) {
				sb.append((char) c);
			} else if (c == 32) {
				sb.append("%20");
			} else if (c == 45 || c == 95 || c == 46 || c == 33 || c == 126 || c == 42 || c == 39 || c == 40
					|| c == 41) {
				sb.append((char) c);
			} else if (c <= 127) {
				sb.append(hex(c));
			} else if (c <= 2047) {
				sb.append(hex(0xC0 | c >> 6));
				sb.append(hex(0x80 | c & 0x3F));
			} else {
				sb.append(hex(0xE0 | c >> 12));
				sb.append(hex(0x80 | c >> 6 & 0x3F));
				sb.append(hex(0x80 | c & 0x3F));
			}
		}
		return sb.toString();
	}

	private static String hex(int i) {
		String s = Integer.toHexString(i);
		return "%".concat(s.length() < 2 ? "0" : "").concat(s);
	}

}
