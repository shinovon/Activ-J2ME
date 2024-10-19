import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.Item;
import javax.microedition.lcdui.ItemCommandListener;
import javax.microedition.lcdui.Spacer;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONObject;

public class ActivApp extends MIDlet implements Runnable, CommandListener, ItemCommandListener {
	
	// threading tasks
	private static final int RUN_SEND_OTP = 1;
	private static final int RUN_AUTH = 2;
	private static final int RUN_AUTH_ACTION = 3;
	private static final int RUN_MAIN = 4;
	private static final int RUN_SWITCH_ACCOUNT = 5;
	
	// login states
	private static final int STATE_SENDING_CODE = 1;
	private static final int STATE_CODE_SENT = 2;
	private static final int STATE_CHECKING_CODE = 3;
	private static final int STATE_LOGGED_IN = 4;
	
	private static final String AUTH_RECORDNAME = "activauth";
	
	private static final String APIURL = "https://activ.kz/";

	// fonts
	private static final Font largefont = Font.getFont(0, 0, Font.SIZE_LARGE);
	private static final Font medboldfont = Font.getFont(0, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
	private static final Font medfont = Font.getFont(0, 0, Font.SIZE_MEDIUM);
	private static final Font smallboldfont = Font.getFont(0, Font.STYLE_BOLD, Font.SIZE_SMALL);
	private static final Font smallfont = Font.getFont(0, 0, Font.SIZE_SMALL);
	
	// midp lifecycle
	private static Display display;
	private static ActivApp midlet;
	private static boolean started;
	
	// commands
	private static Command exitCmd = new Command("Exit", Command.EXIT, 1);
	
	private static Command sendSmsCmd = new Command("Send SMS", Command.OK, 2);
	private static Command confirmSmsCmd = new Command("Confirm", Command.OK, 2);
	
	// ui
	private static Form mainForm;

	private static TextField phoneField;
	private static TextField smsCodeField;
	
	// auth
	private static int loginState;
	private static String accessToken;
	private static String refreshToken;
	private static String phoneNumber;
	private static String uuid;
	private static String currentMsisdn;
	private static long accessTokenTime;
	private static long refreshTokenTime;
	private static int expiresIn;
	private static int subscriberId;
	
	// threading
	private static int run;
	private static int authRun;
	private static boolean running;

	public ActivApp() {
		midlet = this;
	}

	protected void destroyApp(boolean unconditional) {

	}

	protected void pauseApp() {

	}

	protected void startApp() {
		if (started) return;
		started = true;
		
		display = Display.getDisplay(this);
		display.setCurrent(new Form("Activ"));
		
		try {
			RecordStore r = RecordStore.openRecordStore(AUTH_RECORDNAME, false);
			JSONObject j = JSONObject.parseObject(new String(r.getRecord(1), "UTF-8"));
			r.closeRecordStore();

			loginState = j.getInt("loginState", 0);
			accessToken = j.getString("accessToken", null);
			refreshToken = j.getString("refreshToken", null);
			phoneNumber = j.getString("phoneNumber", null);
			uuid = j.getString("uuid", null);
			currentMsisdn = j.getString("currentMsisdn", null);
			accessTokenTime = j.getLong("accessTime", 0);
			refreshTokenTime = j.getLong("refreshTime", 0);
			expiresIn = j.getInt("expiresIn", 0);
			subscriberId = j.getInt("subscriberId", 0);
		} catch (Exception e) {}
		
		if (uuid == null) { // TODO check
			StringBuffer sb = new StringBuffer();
			Random rng = new Random();
			for (int i = 0; i < 16; i++) {
				byte b = (byte) (rng.nextInt() & 0xff);
				sb.append(Integer.toHexString(b >> 4 & 0xf));
				sb.append(Integer.toHexString(b & 0xf));
			}
			uuid = sb.insert(8, '-').insert(13, '-').insert(18, '-').insert(23, '-')
					.toString();
		}
		
		if (loginState == STATE_LOGGED_IN) {
			authRun = RUN_MAIN;
			start(RUN_AUTH_ACTION);
			return;
		}
		display(loginForm());
	}

	public void commandAction(Command c, Displayable d) {
		if (c == exitCmd) {
			notifyDestroyed();
		}
		if (c == sendSmsCmd) {
			if (running || loginState != 0) return;
			loginState = STATE_SENDING_CODE;
			String s = clearNumber(phoneField.getString());
			if (s.startsWith("+")) s = s.substring(1);
			if (s.length() != 11) {
				loginState = 0;
				return;
			}
			
			phoneNumber = s;

			display(loadingAlert(), d);
			start(RUN_SEND_OTP);
			return;
		}
		if (c == confirmSmsCmd) {
			if (running || loginState == STATE_CHECKING_CODE) return;
			loginState = STATE_CHECKING_CODE;
			String s = clearNumber(smsCodeField.getString());
			if (s.length() != 6) {
				loginState = STATE_CODE_SENT;
				return;
			}
			
			display(loadingAlert(), d);
			start(RUN_AUTH);
			return;
		}
	}

	public void commandAction(Command c, Item item) {
		commandAction(c, display.getCurrent());
	}

	public void run() {
		int run;
		synchronized(this) {
			run = ActivApp.run;
			notify();
		}
		running = true;
		System.out.println("run ".concat(n(run)));
		switch (run) {
		case RUN_SEND_OTP: {
			try {
				JSONObject j = (JSONObject) apiPost("api/v1/auth/send-otp-by-sms",
						("{\"operator\":\"ACTIV\",\"msisdn\":\"" + phoneNumber + "\"}").getBytes(),
						"application/json");
			
				if (j.has("error")) {
					loginState = 0;
					display(errorAlert(j.getString("message")));
					return;
				}
				
				phoneNumber = j.getString("recipient");
				
				loginState = STATE_CODE_SENT;
				Form f = new Form("Confirmation of number");
				f.addCommand(exitCmd);
				f.setCommandListener(this);
				
				smsCodeField = new TextField("SMS code", "", 6, TextField.NUMERIC);
				smsCodeField.addCommand(confirmSmsCmd);
				smsCodeField.setDefaultCommand(confirmSmsCmd);
				smsCodeField.setItemCommandListener(this);
				f.append(smsCodeField);
				
				StringItem s = new StringItem("", "Confirm", Item.BUTTON);
				s.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_BEFORE);
				s.addCommand(confirmSmsCmd);
				s.setDefaultCommand(confirmSmsCmd);
				s.setItemCommandListener(this);
				f.append(s);
				
				display.setCurrent(f);
			} catch (Exception e) {
				e.printStackTrace();
				display(errorAlert(e.toString()));
			}
			break;
		}
		case RUN_AUTH: {
			JSONObject j = new JSONObject();
			j.put("operator", "ACTIV");
			
			if (loginState == STATE_LOGGED_IN) {
				j.put("grant_type", "refresh_token");
				j.put("refresh_token", refreshToken);
			} else {
				j.put("grant_type", "sms");
				j.put("username", phoneNumber);
				j.put("code", clearNumber(smsCodeField.getString()));
			}
			
			try {
				j = (JSONObject) apiPost("api/v1/auth/oauth/token", j.toString().getBytes(),
						"application/json");
			
				if (j.has("error")) {
					if (loginState == STATE_LOGGED_IN) {
						loginState = 0;
						display(errorAlert(j.getString("message")), loginForm());
						return;
					}
					loginState = STATE_CODE_SENT;
					display(errorAlert(j.getString("message")));
					return;
				}
				
				accessToken = j.getString("access_token");
				refreshToken = j.getString("refresh_token");
				
				if (loginState != STATE_LOGGED_IN) {
					loginState = STATE_LOGGED_IN;
					
					j = (JSONObject) api("api/v1/profile/login");
					if (!"SUCCESS".equals(j.getString("status"))) {
						loginState = 0;
						display(errorAlert("Log in failed"), loginForm());
						return;
					}
					
					currentMsisdn = phoneNumber;
					refreshTokenTime = System.currentTimeMillis();
				}
				
				if (subscriberId == 0) {
					j = (JSONObject) api("api/v1/optionals/getSubscriberId");
					subscriberId = j.getInt("subscriberId");
				}
				
				accessTokenTime = System.currentTimeMillis();
				
				writeAuth();
				
				if (authRun == 0) {
					display(mainForm = mainForm());
					ActivApp.run = RUN_MAIN;
					run();
					return;
				}
			} catch (Exception e) {
				e.printStackTrace();
				loginState = 0;
				display(errorAlert(e.toString()), loginForm());
			}
			break;
		}
		case RUN_AUTH_ACTION: {
			Displayable f = display.getCurrent();
			
			long now = System.currentTimeMillis();
			if (now - accessTokenTime >= expiresIn * 1000L - 2000L)
				accessToken = null;
			
			if (accessToken == null) {
				Alert a = loadingAlert();
				a.setString("Authorizing");
				display(a, f);
				
				ActivApp.run = RUN_AUTH;
				run();
				
				display(f);
			}
			
			if (authRun != 0) {
				ActivApp.run = authRun;
				authRun = 0;
				run();
				return;
			}
			break;
		}
		case RUN_MAIN: {
			// TODO
			Form f = mainForm();
			f.deleteAll();
			
			try {
				JSONArray j;
				{
					String v = "{\"msisdn\":\"".concat(currentMsisdn).concat("\"}");
					
					StringBuffer sb = new StringBuffer();
					sb.append("[{\"operationName\":\"Profile\",\"variables\":")
					.append(v)
					.append(",\"query\":\"query Profile($msisdn: String) {\\n  Profile(msisdn: $msisdn) {\\n    id: msisdn\\n    isConfirmed\\n    isFullNameConfirmed\\n    fullName {\\n      firstName\\n      lastName\\n      middleName\\n      __typename\\n    }\\n    avatar {\\n      link\\n      __typename\\n    }\\n    project\\n    tariff {\\n      nextTariffDebitingDate\\n      chargeState\\n      id\\n      name\\n      cost\\n      duration {\\n        id\\n        name\\n        __typename\\n      }\\n      externalIdentifier: external_identifier\\n      __typename\\n    }\\n    customerStatus {\\n      displayText: display_text\\n      link\\n      externalId: external_id\\n      description: description\\n      __typename\\n    }\\n    phoneNumber: msisdn\\n    status\\n    email\\n    parentAccounts {\\n      name\\n      status\\n      mainMsisdn\\n      childMsisdn\\n      id\\n      newSettings {\\n        description\\n        id\\n        isEnabled: is_enabled\\n        modifiable\\n        operation\\n        __typename\\n      }\\n      __typename\\n    }\\n    childAccounts {\\n      name\\n      status\\n      mainMsisdn\\n      childMsisdn\\n      id\\n      __typename\\n    }\\n    cvmBonus {\\n      amount\\n      billingId\\n      startDate\\n      endDate\\n      maxAmount\\n      displayValue\\n      __typename\\n    }\\n    bundleServices {\\n      title: name\\n      isActivated\\n      __typename\\n    }\\n    packageBonusGroup {\\n      amount\\n      billingId\\n      displayValue\\n      endDate\\n      maxAmount\\n      name\\n      type\\n      unit {\\n        name\\n        code\\n        __typename\\n      }\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"},{\"operationName\":\"BalanceInfo\",\"variables\":")
					.append(v)
					.append(",\"query\":\"query BalanceInfo($msisdn: String) {\\n  Profile(msisdn: $msisdn) {\\n    id: msisdn\\n    balanceInfo {\\n      id: balance\\n      value: balance\\n      date: actualityDate\\n      creditLimit\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"},{\"operationName\":\"ProfileTariff\",\"variables\":")
					.append(v)
					.append(",\"query\":\"query ProfileTariff($msisdn: String) {\\n  Profile(msisdn: $msisdn) {\\n    id: msisdn\\n    dailyBonusGroup {\\n      name\\n      balanceGroupId\\n      bonuses: convertedBonuses {\\n        name\\n        startDate\\n        endDate\\n        amount\\n        type\\n        direction\\n        billingId\\n        maxAmount\\n        unlim\\n        unit {\\n          name\\n          code\\n          __typename\\n        }\\n        __typename\\n      }\\n      __typename\\n    }\\n    customerStatus {\\n      displayText: display_text\\n      link\\n      externalId: external_id\\n      description: description\\n      __typename\\n    }\\n    fullCost\\n    tariff {\\n      nextTariffDebitingDate\\n      button_choose_tariff\\n      id\\n      external_identifier\\n      name\\n      cost\\n      cost_old\\n      date_reprice\\n      stepsHider: stepshider\\n      showNotifChangeGb: show_notif_change_gigi\\n      duration {\\n        id\\n        name\\n        __typename\\n      }\\n      category {\\n        id\\n        code\\n        __typename\\n      }\\n      type {\\n        id\\n        commands {\\n          id\\n          code\\n          name\\n          icon\\n          iconLight: icon_light_theme\\n          isActivated: is_activated\\n          isDisabled: is_disabled\\n          isNewCommand: new_command\\n          sort\\n          addInfo: add_info\\n          link\\n          contactCode: contract_code {\\n            code\\n            description\\n            __typename\\n          }\\n          __typename\\n        }\\n        __typename\\n      }\\n      status {\\n        code\\n        __typename\\n      }\\n      limits {\\n        balance\\n        unit {\\n          name\\n          code\\n          __typename\\n        }\\n        limits {\\n          billingType: type_billing {\\n            direction\\n            type\\n            __typename\\n          }\\n          short_description\\n          unlimIcon: unlim_icon\\n          iconList: list_of_icon(_sort: \\\"sort:asc\\\") {\\n            icon\\n            code\\n            __typename\\n          }\\n          __typename\\n        }\\n        __typename\\n      }\\n      bonusGroup {\\n        bonuses {\\n          displayValue\\n          title: name\\n          unlim\\n          type\\n          direction\\n          unit {\\n            name\\n            code\\n            __typename\\n          }\\n          endDate\\n          amount\\n          maxAmount\\n          unlimType\\n          unlimThreshold {\\n            isEnding\\n            __typename\\n          }\\n          bonusThreshold {\\n            code\\n            thresholdPercent\\n            isEnding\\n            __typename\\n          }\\n          hasAddOn\\n          __typename\\n        }\\n        __typename\\n      }\\n      repriseMessage: reprise_message {\\n        nameMessage: name_message\\n        turnOn: turn_on\\n        deeplinkMessage: deeplink_message {\\n          mainUrl: main_url\\n          id\\n          __typename\\n        }\\n        __typename\\n      }\\n      widgetInfo {\\n        title\\n        templateTitle: template_title\\n        description\\n        buttonName: button_name\\n        code\\n        buttonLink: button_link {\\n          mainUrl: main_url\\n          __typename\\n        }\\n        arrowLink: arrow_button_link {\\n          mainUrl: main_url\\n          __typename\\n        }\\n        __typename\\n      }\\n      addPackages: add_packages {\\n        billingId: billing_id\\n        title\\n        code\\n        amount\\n        connectionId: connection_id\\n        packageType: package_type {\\n          filterName: filter_name\\n          filterTagPackages: filter_tag_packages\\n          __typename\\n        }\\n        altConnectionMethods: alt_connection_methods {\\n          title\\n          description\\n          ussd\\n          __typename\\n        }\\n        detailedConditionsPackages: detailed_conditions_packages {\\n          title\\n          description\\n          __typename\\n        }\\n        currency {\\n          name\\n          code\\n          __typename\\n        }\\n        __typename\\n      }\\n      __typename\\n    }\\n    bundleServices {\\n      title: name\\n      isActivated\\n      connectionCost\\n      costConnection: cost_connection\\n      code\\n      iconDark: icon_dark\\n      iconLight: icon_light\\n      linkToLending: link_to_lending\\n      serviceIconWhite: service_icon_white\\n      serviceIconBlack: service_icon_black\\n      externalKey: external_key\\n      unit {\\n        code\\n        name\\n        __typename\\n      }\\n      __typename\\n    }\\n    bonusSum {\\n      name\\n      amount\\n      bonusDirections: bonus_directions {\\n        hideMyServices: hide_my_services\\n        __typename\\n      }\\n      maxAmount\\n      unit {\\n        name\\n        code\\n        __typename\\n      }\\n      unitValue\\n      displayValue\\n      displayValueMax\\n      unlim\\n      unlimType\\n      bonusThreshold {\\n        isEnding\\n        __typename\\n      }\\n      unlimThreshold {\\n        isEnding\\n        __typename\\n      }\\n      hasAddOn\\n      sort\\n      type\\n      linkForLimit: link_for_limit {\\n        deeplinkTypes: deeplink_types {\\n          codeType: code_type\\n          __typename\\n        }\\n        mainUrl: main_url\\n        __typename\\n      }\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}]");
				
					j = (JSONArray) apiPost("graphql", sb.toString().getBytes(), "application/json");
				}
				System.out.println(j);
				
				JSONObject t = j.getObject(0).getObject("data").getObject("Profile");
				
				StringItem s;
				Spacer p;
				Gauge g;
				
				// номер телефона
				s = new StringItem("", "+".concat(t.getString("id")));
				s.setFont(medboldfont);
				s.setLayout(Item.LAYOUT_LEFT | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
				mainForm.append(s);
				
				// имя
				JSONObject fullname = t.getObject("fullName");
				s = new StringItem("", fullname.getString("firstName").concat(" ").concat(fullname.getString("lastName", "")));
				s.setFont(smallfont);
				s.setLayout(Item.LAYOUT_LEFT | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
				mainForm.append(s);
				
				
				p = new Spacer(16, 16);
				p.setLayout(Item.LAYOUT_LEFT | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
				mainForm.append(p);
				

				t = j.getObject(2).getObject("data").getObject("Profile").getObject("tariff");
				
				// название тарифа
				s = new StringItem("", t.getString("name"));
				s.setFont(smallboldfont);
				s.setLayout(Item.LAYOUT_LEFT | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
				mainForm.append(s);
				
				// цена тарифа
				s = new StringItem("", t.getString("cost").concat(" tenge, due ").concat(t.getString("nextTariffDebitingDate")));
				s.setFont(smallfont);
				s.setLayout(Item.LAYOUT_LEFT | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
				mainForm.append(s);
				
				// баланс
				s = new StringItem("Balance",
						j.getObject(1).getObject("data").getObject("Profile").getObject("balanceInfo").getString("value")
						+ " T");
				s.setFont(smallfont);
				s.setLayout(Item.LAYOUT_LEFT | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
				mainForm.append(s);
				
				
				p = new Spacer(16, 16);
				p.setLayout(Item.LAYOUT_LEFT | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
				mainForm.append(p);
				
				// остаток тарифа
				
				s = new StringItem("", "Available");
				s.setFont(medboldfont);
				s.setLayout(Item.LAYOUT_LEFT | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
				mainForm.append(s);
				
				JSONArray sum = j.getObject(2).getObject("data").getObject("Profile").getArray("bonusSum");
				int l = sum.size();
				
				for(int i = 0; i < l; ++i) {
					JSONObject k = sum.getObject(i);
					g = new Gauge(k.getString("name")
							+ " (" + (k.getBoolean("unlim", false) ?
									"Unlimited" : (k.getString("displayValue") + " of " + k.getString("displayValueMax"))) + ")"
							, false
							, (int) (k.getDouble("maxAmount") * 10D)
							, (int) (k.getDouble("amount") * 10D));
					g.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER);
					mainForm.append(g);
				}

				if (mainForm == f) 
					display(f);
			} catch (Exception e) {
				e.printStackTrace();
				display(errorAlert(e.toString()), f);
			}
			break;
		}
		case RUN_SWITCH_ACCOUNT: {
			// TODO
			break;
		}
		}
		running = false;
	}
	
	private Thread start(int i) {
		Thread t = null;
		try {
			synchronized(this) {
				run = i;
				(t = new Thread(this)).start();
				wait();
			}
		} catch (Exception e) {}
		return t;
	}
	
	private static void writeAuth() {
		try {
			RecordStore.deleteRecordStore(AUTH_RECORDNAME);
		} catch (Exception e) {}
		try {
			JSONObject j = new JSONObject();

			j.put("loginState", loginState);
			j.put("accessToken", accessToken);
			j.put("refreshToken", refreshToken);
			j.put("uuid", uuid);
			j.put("currentMsisdn", currentMsisdn);
			j.put("accessTime", accessTokenTime);
			j.put("refreshTime", refreshTokenTime);
			j.put("phoneNumber", phoneNumber);
			j.put("expiresIn", expiresIn);
			j.put("subscriberId", subscriberId);
			
			byte[] b = j.toString().getBytes("UTF-8");
			RecordStore r = RecordStore.openRecordStore(AUTH_RECORDNAME, true);
			r.addRecord(b, 0, b.length);
			r.closeRecordStore();
		} catch (Exception e) {}
	}

	private Form loginForm() {
		Form f = new Form("Log in");
		f.setCommandListener(this);
		f.addCommand(exitCmd);
		
		phoneField = new TextField("Phone number", "", 30, TextField.PHONENUMBER);
		phoneField.addCommand(sendSmsCmd);
		f.append(phoneField);
		
		StringItem s = new StringItem("", "Continue", Item.BUTTON);
		s.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_BEFORE);
		s.addCommand(sendSmsCmd);
		s.setDefaultCommand(sendSmsCmd);
		s.setItemCommandListener(this);
		f.append(s);
		
		return f;
	}
	
	private Form mainForm() {
		if (mainForm != null) return mainForm;
		Form f = new Form("Activ");
		f.setCommandListener(midlet);
		f.addCommand(exitCmd);
		mainForm = f;
		
		return f;
	}
	
	static void display(Alert a, Displayable d) {
		if (d == null) {
			display.setCurrent(a);
			return;
		}
		display.setCurrent(a, d);
	}
	
	private static void display(Displayable d) {
		if (mainForm != null && d instanceof Alert) {
			display.setCurrent((Alert) d, mainForm);
			return;
		}
		display.setCurrent(d);
	}

	private static Alert errorAlert(String text) {
		Alert a = new Alert("");
		a.setType(AlertType.ERROR);
		a.setString(text);
		a.setTimeout(3000);
		return a;
	}
	
	private static Alert infoAlert(String text) {
		Alert a = new Alert("");
		a.setType(AlertType.CONFIRMATION);
		a.setString(text);
		a.setTimeout(1500);
		return a;
	}
	
	private static Alert loadingAlert() {
		Alert a = new Alert("", "Loading..", null, null);
		a.setCommandListener(midlet);
		a.addCommand(Alert.DISMISS_COMMAND);
		a.setIndicator(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
		a.setTimeout(30000);
		return a;
	}
	
	private static Object api(String url) throws IOException {
		Object res;

		HttpConnection hc = null;
		InputStream in = null;
		try {
			hc = open(proxyUrl(APIURL.concat(url)));
			hc.setRequestMethod("GET");
			int c;
			if ((c = hc.getResponseCode()) >= 500) {
				throw new IOException("HTTP ".concat(Integer.toString(c)));
			}
			res = JSONObject.parseJSON(readUtf(in = hc.openInputStream(), (int) hc.getLength()));
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
	
	private static Object apiPost(String url, byte[] body, String type) throws IOException {
		Object res;

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
			if ((c = hc.getResponseCode()) >= 500) {
				throw new IOException("HTTP ".concat(Integer.toString(c)));
			}
			res = JSONObject.parseJSON(readUtf(in = hc.openInputStream(), (int) hc.getLength()).trim());
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
	
	private static HttpConnection open(String url) throws IOException {
		HttpConnection hc = (HttpConnection) Connector.open(url);
		
		hc.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0");
		hc.setRequestProperty("Accept", "application/json, text/plain, */*");
		hc.setRequestProperty("Accept-Language", "en");
		hc.setRequestProperty("Origin", "https://activ.kz");
		
		hc.setRequestProperty("Authorization", accessToken == null ? "Basic V0VCOg==" : "Bearer ".concat(accessToken));
		
		hc.setRequestProperty("X-Platform", "WEB");
		hc.setRequestProperty("X-Customer-Operator-type", "ACTIV");
		hc.setRequestProperty("X-Current-Customer-MSISDN", currentMsisdn != null ? currentMsisdn : "");
		hc.setRequestProperty("X-Device-Name", "Win32"); // TODO
		if (uuid != null) hc.setRequestProperty("X-device-uuid", uuid);
		
		StringBuffer sb = new StringBuffer
				("platform=web; brand=activ; locale=en; NEXT_LOCALE=en; remember_me_checked={%22isChecked%22:true}");
		// TODO ?
		sb.append("; region=Almaty; region_id=50000; city_id=50000; region_cover_id=40000; region_or_city_id=47; loyalty_city=1");
		
		if (loginState == STATE_LOGGED_IN) {
			if (currentMsisdn != null) {
				sb.append("; phone_number=").append(phoneNumber);
				if (!currentMsisdn.equals(phoneNumber)) {
					sb.append("; child_phone_number=").append(phoneNumber);
				}
			}
			if (uuid != null)
				sb.append("; uuid=").append(uuid);
			if (accessToken != null)
				sb.append("; access_token=").append(accessToken);
			if (refreshToken != null)
				sb.append("; refresh_token=").append(refreshToken);
			if (subscriberId != 0)
				sb.append("; getSubscriberId=").append(subscriberId);
			if (expiresIn > 0)
				sb.append("; expires_in=").append(expiresIn).append("000");
		}
		
		
		hc.setRequestProperty("Cookie", sb.toString());
		
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
	
	static String n(int n) {
		if (n < 10) {
			return "0".concat(Integer.toString(n));
		} else return Integer.toString(n);
	}
	
	private static String clearNumber(String s) {
		StringBuffer t = new StringBuffer();
		int l = s.length();
		char c;
		for (int i = 0; i < l; i++) {
			if ((c = s.charAt(i)) == ' ' || c == '(' || c == ')') continue;
			t.append(c);
		}

		return t.toString().toLowerCase().trim();
	}

}
