package com.devicewise.dwsensormonitor;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

public class DwService extends Service implements MqttCallback, LocationListener, SensorEventListener {

	String server;
	String apptoken;
	boolean fast_pub;
	String thingkey;
	MqttClient client;

	long publish_interval = 10000;

	SensorManager sensorManager;
	Map<String, Long> lastPublishMap;
	long lightLastPublish;

	boolean lightProximity = false;

	LocationManager locationManager;
	String locationProvider;
	
	boolean is_cellular=true;
	TelephonyManager telephonyManager;
	
	private PhoneStateListener phoneStateListener = new PhoneStateListener() {
		@Override
		public void onSignalStrengthsChanged (SignalStrength signalStrength) {
			
			Long lastPubTime = lastPublishMap.get("rssi");

			if (lastPubTime == null || (lastPubTime + publish_interval < System.currentTimeMillis())) {
				lastPublishMap.put("rssi", System.currentTimeMillis());
			
				String ssignal = signalStrength.toString();
				String[] parts = ssignal.split(" ");
				
				int dbm = 0;
				if(telephonyManager.getNetworkType()==TelephonyManager.NETWORK_TYPE_LTE && parts.length>=9) {
					dbm = Integer.parseInt(parts[8])*2-113;
				} else if(signalStrength.isGsm()) {
					int str = signalStrength.getGsmSignalStrength();
					if(str!=99) {
						dbm = -113+(2*str);
					}
				}
				if(dbm!=0) {
					publishProperty("rssi", dbm);
				}
			}
		}
	};

	private BroadcastReceiver receiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			try {
				switch(intent.getAction()) {
				case Intent.ACTION_POWER_CONNECTED:
				case Intent.ACTION_POWER_DISCONNECTED:
				case Intent.ACTION_BATTERY_CHANGED:
					int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
					switch (status) {
					case BatteryManager.BATTERY_STATUS_CHARGING:
						publishAlarm("batterycharge",1);
						break;
					case BatteryManager.BATTERY_STATUS_DISCHARGING:
						publishAlarm("batterycharge",0);
						break;
					case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
					case BatteryManager.BATTERY_STATUS_FULL:
						publishAlarm("batterycharge",2);
						break;
					}
					
					Long lastPubTime = lastPublishMap.get("battery_level");

					if (lastPubTime == null || (lastPubTime + publish_interval < System.currentTimeMillis())) {
						lastPublishMap.put("battery_level", System.currentTimeMillis());
					
						int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
						int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
		
						float batteryPct = level * 100 / (float)scale;
						
						if(batteryPct>75) {
							publishAlarm("batterylevel",3);
						} else if(batteryPct>40) {
							publishAlarm("batterylevel",2);
						} else if(batteryPct>25) {
							publishAlarm("batterylevel",1);
						} else {
							publishAlarm("batterylevel",0);
						}
						
						publishProperty("battery",Float.parseFloat(String.format("%.1f", batteryPct)));
					}
					break;
				case Intent.ACTION_SCREEN_OFF:
					publishAlarm("screen",0);
					break;
				case Intent.ACTION_SCREEN_ON:
					publishAlarm("screen",1);
					break;
				//case Intent.ACTION_USER_PRESENT:
				}
			} catch (Exception e) {

			}
		}

	};

	@Override
	public void onCreate() {
		super.onCreate();

		Log.i("service", "onCreate()");

		lastPublishMap = new HashMap<String, Long>();

		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

		if(telephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_NONE) {
			is_cellular=false;
		} else {
			is_cellular=true;
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		Log.i("dw.service", "onDestroy()");
		unregisterListeners();
		if(client!=null && client.isConnected()) {
			try {
				client.disconnect();
			} catch(Exception e) {
				
			}
		}
		foregroundStop();
		
	}

	private static final String PREFS_NAME = "dwSensorMonitor";

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.i("dw.service", "onStartCommand()");

		if (intent == null) {
			Log.i("dw.service", "intent is null");
			SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
		    String server = settings.getString("server", "open-api.devicewise.com");
		    String apptoken = settings.getString("apptoken", "OPEN-ANDROID");
		    boolean connected = settings.getBoolean("connected", false);
		    boolean fast_pub = settings.getBoolean("fast_pub", true);
		    if(!connected) {
		    	return Service.START_STICKY;
		    }
		    intent = new Intent();
			intent.setAction("connect");
			intent.putExtra("server", server);
			intent.putExtra("apptoken", apptoken);		   
			intent.putExtra("fast_pub", fast_pub);
		}

		if (client == null) {
			Log.i("dw.service", "mqtt client is null");
		}

		String action = intent.getAction();

		if (action == "connect") {
			Log.i("dw.service", "action: connect");	
			
			if(client!=null && client.isConnected()) {
				Log.i("dw.service", "already connected");
				return Service.START_STICKY;
			}
			
			foregroundStart();

			server = intent.getStringExtra("server");
			apptoken = intent.getStringExtra("apptoken");
			fast_pub = intent.getBooleanExtra("fast_pub", true);
			
			if(fast_pub) {
				publish_interval = 10000;
			} else {
				publish_interval = 300000;
			}

			connect();
			
			registerListeners();
			
			sayHello();

		} else if (action == "disconnect") {
			Log.i("dw.service", "action: disconnect");

			unregisterListeners();
			
			stopForeground(true);

			try {
				if (client != null) {
					client.disconnect();
				}
			} catch (MqttException me) {
				Log.i("dw.service", "Disconnect Exception: " + me.getMessage());
			}

			broadcastConnectionStateChange(false);
		} else if (action == "status") {
			if(client==null) {
				broadcastConnectionStateChange(false);
			} else {
				broadcastConnectionStateChange(client.isConnected());
			}
			if(locationProvider!=null) {
				Location location = locationManager.getLastKnownLocation(locationProvider);
				if (location != null) {
					onLocationChanged(location);
				}
			}
		} else if (action == "switchAlarm") {
			boolean switchAlarm = intent.getBooleanExtra("state", false);
			Log.i("dw.service", "Got switch alarm: " + Boolean.toString(switchAlarm));
			if (switchAlarm) {
				publishAlarm("switch",1);
			} else {
				publishAlarm("switch",0);
			}
		}

		return Service.START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	public void broadcastConnectionStateChange(boolean connected) {
		Intent intent = new Intent();
		intent.setAction("com.devicewise.CONNECTION_STATE");
		intent.putExtra("state", connected);
		sendBroadcast(intent);
	}

	public void connect() {
		Log.i("dw.service", "connect()");
		try {
			if (client != null && client.isConnected()) {
				client.disconnect();
			}
			
			if(is_cellular) {
				thingkey = telephonyManager.getDeviceId();
			} else {
				thingkey = Build.SERIAL;
			}

			client = new MqttClient("ssl://" + server + ":8883", thingkey, new MemoryPersistence());

			MqttConnectOptions options = new MqttConnectOptions();
			options.setCleanSession(true);
			options.setUserName(thingkey);
			options.setPassword(apptoken.toCharArray());
			options.setKeepAliveInterval(60);
			client.setCallback(this);
			try {
				Log.i("dw.service", "Connecting to server:" + server + " with apptoken:" + apptoken);
				client.connect(options);
				Log.i("dw.service", "Connected...");
			} catch (MqttException me) {
				Log.i("dw.service", "Connect Exception: " + me.getMessage());
			}

		} catch (MqttException e) {
			Log.i("dw.service", "New Exception: " + e.getMessage());
		}
		broadcastConnectionStateChange(client.isConnected());
		return;
	}

	public void onSensorChanged(SensorEvent event) {

		try {
			String name = event.sensor.getName().toLowerCase().replace(" ", "").replace("&", "");
			Long lastPubTime = lastPublishMap.get(name);

			if (lastPubTime == null || (lastPubTime + publish_interval < System.currentTimeMillis())) {
				lastPublishMap.put(name, System.currentTimeMillis());

				Log.i("dw.service", "Publishing: " + name);

				int len = event.values.length;

				if (client != null && client.isConnected()) {
					if (len == 1) {
						if (event.sensor.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE) {
							publishProperty("temperature", event.values[0]);
						} else if (event.sensor.getType() == Sensor.TYPE_RELATIVE_HUMIDITY) {
							publishProperty("humidity", event.values[0]);
						} else if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
							publishProperty("barometer", event.values[0]);
						} else if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
							publishProperty("light", event.values[0]);
						} else if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
							publishProperty("step_counter", event.values[0]);
						} else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
							publishProperty("proximity", event.values[0]);
						}
					} else if (len >= 3) {
						if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
							publishProperty("light", event.values[0]);
						} else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
							publishProperty("proximity", event.values[0]);
						} else if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
							publishProperty("pressure", event.values[0]);
						} else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
							publishProperty("accelerometer_x", event.values[0]);
							publishProperty("accelerometer_y", event.values[1]);
							publishProperty("accelerometer_z", event.values[2]);
						} else if (event.sensor.getType() == Sensor.TYPE_ORIENTATION) {
							publishProperty("orientation_x", event.values[0]);
							publishProperty("orientation_y", event.values[1]);
							publishProperty("orientation_z", event.values[2]);
						}
					}
				}
			}
		} catch (Exception e) {
			Log.i("dw.service", e.getMessage());
		}
	}

	public void publishProperty(String name, float value) {
		Intent intent = new Intent();
		intent.setAction("com.devicewise.SENSOR_UPDATE");
		intent.putExtra("name", name);
		intent.putExtra("value", value);
		sendBroadcast(intent);
		
		if(client!=null && client.isConnected()) {
			MqttMessage message = new MqttMessage();
			message.setPayload(Float.toString(value).getBytes());
			try {
				client.publish("me/property/" + name, message);
			} catch (Exception e) {
				Log.i("dw.service", e.getMessage());
			}
		}
	}
	
	public void publishAttribute(String name, String value) {
		if(client!=null && client.isConnected()) {
			MqttMessage message = new MqttMessage();
			message.setPayload(value.getBytes());
			try {
				client.publish("me/attribute/" + name, message);
			} catch (Exception e) {
				Log.i("dw.service", e.getMessage());
			}
		}
	}
	
	public void publishAlarm(String name, int state) {
		if(client!=null && client.isConnected()) {
			MqttMessage message = new MqttMessage();
			message.setPayload(Integer.toString(state).getBytes());
			try {
				client.publish("me/alarm/" + name, message);
			} catch (Exception e) {
				Log.i("dw.service", e.getMessage());
			}
		}
	}

	@Override
	public void onLocationChanged(Location location) {
		if (client == null || !client.isConnected()) {
			return;
		}
		Log.i("dw.service", "Publishing: location.");
		double lat = (double) (location.getLatitude());
		double lng = (double) (location.getLongitude());

		Intent intent = new Intent();
		intent.setAction("com.devicewise.LOCATION_UPDATE");
		intent.putExtra("lat", lat);
		intent.putExtra("lng", lng);
		intent.putExtra("fix", locationProvider);
		intent.putExtra("acc", location.getAccuracy());
		sendBroadcast(intent);

		MqttMessage message = new MqttMessage();
		String cmd = "{\"1\":{\"command\":\"location.publish\",\"params\":{\"lat\":" + Double.toString(lat) + ",\"lng\":" + Double.toString(lng) + ",\"heading\":" + location.getBearing() + ",\"speed\":" + location.getSpeed() + ",\"fixAcc\":" + location.getAccuracy() + ",\"fixType\":\"" + locationProvider + "\"}}}";
		message.setPayload(cmd.getBytes());
		try {
			client.publish("api", message);
		} catch (Exception e) {
			Log.i("dw.service", e.getMessage());
		}
	}

	@Override
	public void connectionLost(Throwable cause) {
		Log.i("dw.service", "Connection Lost!");
		Timer timer = new Timer();
		timer.schedule(new TimerTask() {          
			@Override
			public void run() {
				Log.i("dw.service", "Trying to reconnect.");
				try {
					connect();
					Log.i("dw.service", "Connection Lost! (reconnected)");
				} catch(Exception e) {
					Log.i("dw.service", "Connection Lost! (couldn't reconnect)");
					broadcastConnectionStateChange(false);
					stopSelf();
				}		
			}
		}, 5000);
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken arg0) {

	}

	@Override
	public void messageArrived(String arg0, MqttMessage arg1) throws Exception {
	}

	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
		Log.i("dw.service", "Status Change: " + provider + " / " + Integer.toString(status));
		
	}

	@Override
	public void onProviderEnabled(String provider) {
		Log.i("dw.service", "Provider Enabled: " + provider);
		findBestLocationProvider();
	}

	@Override
	public void onProviderDisabled(String provider) {
		Log.i("dw.service", "Provider Disabled: " + provider);
		findBestLocationProvider();
	}
	
	private void sayHello() {
		try {
			publishAttribute("brand",Build.BRAND);
			publishAttribute("manufacturer",Build.MANUFACTURER);
			publishAttribute("model",Build.MODEL);
			if(is_cellular) {
				publishAttribute("radio",Build.getRadioVersion());
			}
			publishAttribute("version",Build.VERSION.RELEASE);

			if(is_cellular) {
				if(telephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
					publishAttribute("phone_type","GSM");
					publishAttribute("network",telephonyManager.getNetworkOperatorName());
					
					String cmd = "{\"1\":{\"command\":\"thing.update\",\"params\":{\"iccid\":\"" + telephonyManager.getSimSerialNumber() + "\"}}}";
					MqttMessage message = new MqttMessage();
					message.setPayload(cmd.getBytes());
					client.publish("api", message);
					
					cmd = "{\"1\":{\"command\":\"thing.tag.add\",\"params\":{\"tags\":[\"gsm\",\""+Build.BRAND+"\"]}}}";
					message.setPayload(cmd.getBytes());
					client.publish("api", message);
				} else {
					publishAttribute("phone_type","CDMA");
					
					String cmd = "{\"1\":{\"command\":\"thing.tag.add\",\"params\":{\"tags\":[\"cdma\",\""+Build.BRAND+"\"]}}}";
					MqttMessage message = new MqttMessage();
					message.setPayload(cmd.getBytes());
					client.publish("api", message);
				}
			}
			
		} catch(Exception e) {
			
		}
	}
	
	private void registerListeners() {
		int[] sensors = { 
			Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_RELATIVE_HUMIDITY, Sensor.TYPE_ACCELEROMETER,
			Sensor.TYPE_LIGHT,Sensor.TYPE_PRESSURE,Sensor.TYPE_STEP_COUNTER,Sensor.TYPE_ORIENTATION,Sensor.TYPE_PROXIMITY
		};
		
		for (int s : sensors) {
			Sensor sensor = sensorManager.getDefaultSensor(s);
			if(sensor!=null) {
				Log.i("sensor_enum", "Found Sensor: " + sensor.getName());
				sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
			}
		}

		findBestLocationProvider();
		
		if(is_cellular) {
			telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
		}

		IntentFilter filter = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
		filter.addAction(Intent.ACTION_BATTERY_CHANGED);
		filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
		filter.addAction(Intent.ACTION_SCREEN_OFF);
		filter.addAction(Intent.ACTION_SCREEN_ON);
		//filter.addAction(Intent.ACTION_USER_PRESENT);
		this.registerReceiver(receiver, filter);
	}
	
	private void unregisterListeners() {
		locationManager.removeUpdates(this);
		sensorManager.unregisterListener(this);
		this.unregisterReceiver(receiver);
		if(is_cellular) {
			telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
		}
	}

	private void findBestLocationProvider() {
		
		locationManager.removeUpdates(this);
		
		Criteria criteria = new Criteria();
		locationProvider = locationManager.getBestProvider(criteria, true);
		Log.i("dw.service", "Found provider: " + locationProvider);
		if (locationProvider != null) {
			locationManager.requestLocationUpdates(locationProvider, 60000, 500, this);

			// Initialize the location fields
			Location location = locationManager.getLastKnownLocation(locationProvider);
			if (location != null) {
				onLocationChanged(location);
			}
		}
	}
	
	final static int myID = 1981012599;
	private void foregroundStart() {
		//The intent to launch when the user clicks the expanded notification
		Intent fgintent = new Intent(this, MainActivity.class);
		fgintent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		PendingIntent pendIntent = PendingIntent.getActivity(this, 0, fgintent, 0);

		Notification notification;
		if (Integer.parseInt(Build.VERSION.SDK) >= Build.VERSION_CODES.DONUT) {
		// Build.VERSION.SDK_INT requires API 4 and would cause issues below API 4

		    NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
		    builder.setTicker("DW Demo").setContentTitle("DW Sensor Demo").setContentText("Keeps monitoring sensor data while the phone is asleep.")
		            .setWhen(System.currentTimeMillis()).setAutoCancel(false)
		            .setOngoing(true).setPriority(Notification.PRIORITY_HIGH)
		            .setContentIntent(pendIntent).setSmallIcon(R.drawable.dw);

		    notification = builder.build();

		} else {

			notification = new Notification(R.drawable.dw, "DW Demo", System.currentTimeMillis());
			notification.setLatestEventInfo(this, "DW Sensor Demo", "Keeps monitoring sensor data while the phone is asleep.", pendIntent);

		}
		notification.flags |= Notification.FLAG_NO_CLEAR;
		startForeground(myID, notification);
	}
	private void foregroundStop() {
		stopForeground(true);
	}
}
