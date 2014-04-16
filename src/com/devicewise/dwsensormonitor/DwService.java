package com.devicewise.dwsensormonitor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import android.app.IntentService;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.TextView;
import android.widget.ToggleButton;

public class DwService extends Service implements MqttCallback, LocationListener, SensorEventListener {

	int startId;

	String server;
	String apptoken;
	String imei;
	MqttClient client;

	long publish_interval = 10000;

	SensorManager sensorManager;
	Map<String, Long> lastPublishMap;
	long lightLastPublish;

	boolean lightProximity = false;

	LocationManager locationManager;
	String locationProvider;

	private BroadcastReceiver receiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			try {
				MqttMessage message = new MqttMessage();
				int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
				switch (status) {
				case BatteryManager.BATTERY_STATUS_CHARGING:
					message.setPayload("1".getBytes());
					client.publish("me/alarm/batterycharge", message);
					break;
				case BatteryManager.BATTERY_STATUS_DISCHARGING:
					message.setPayload("0".getBytes());
					client.publish("me/alarm/batterycharge", message);
					break;
				case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
				case BatteryManager.BATTERY_STATUS_FULL:
					message.setPayload("2".getBytes());
					client.publish("me/alarm/batterycharge", message);
					break;
				}
				int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
				int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

				float batteryPct = level * 100 / (float)scale;
				
				if(batteryPct>75) {
					message.setPayload("3".getBytes());
					client.publish("me/alarm/batterylevel", message);
				} else if(batteryPct>40) {
					message.setPayload("2".getBytes());
					client.publish("me/alarm/batterylevel", message);
				} else if(batteryPct>25) {
					message.setPayload("1".getBytes());
					client.publish("me/alarm/batterylevel", message);
				} else {
					message.setPayload("0".getBytes());
					client.publish("me/alarm/batterylevel", message);
				}
				
				message.setPayload(String.format("%.1f", batteryPct).getBytes());
				client.publish("me/property/battery", message);
			} catch (Exception e) {

			}
		}

	};

	@Override
	public void onCreate() {
		super.onCreate();

		Log.i("service", "onCreate()");

		lastPublishMap = new HashMap<String, Long>();

		TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		imei = telephonyManager.getDeviceId();

		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		Log.i("service" + Integer.toString(startId), "onDestroy()");
		if(client!=null && client.isConnected()) {
			try {
				client.disconnect();
			} catch(Exception e) {
				
			}
		}
		stopForeground(true);
	}
	
	final static int myID = 1981012599;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		this.startId = startId;
		Log.i("service" + Integer.toString(startId), "onStartCommand()");

		if (intent == null) {
			Log.i("service" + Integer.toString(startId), "intent is null");
			return Service.START_NOT_STICKY;
		}

		if (client == null) {
			Log.i("service" + Integer.toString(startId), "mqtt client is null");
		}

		String action = intent.getAction();

		if (action == "connect") {
			Log.i("service" + Integer.toString(startId), "action: connect");
			
			
			//The intent to launch when the user clicks the expanded notification
			Intent fgintent = new Intent(this, DwService.class);
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

			server = intent.getStringExtra("server");
			apptoken = intent.getStringExtra("apptoken");

			connect();

			List<Sensor> sl = sensorManager.getSensorList(Sensor.TYPE_ALL);
			for (Sensor sensor : sl) {
				String name = sensor.getName();
				int type = sensor.getType();
				Log.i("sensor_enum", "Found Sensor: " + name + " " + Integer.toString(type));

				switch (type) {
				case Sensor.TYPE_AMBIENT_TEMPERATURE:
				case Sensor.TYPE_RELATIVE_HUMIDITY:
				case Sensor.TYPE_ACCELEROMETER:
					// case Sensor.TYPE_GYROSCOPE:
				case Sensor.TYPE_LIGHT:
					// case Sensor.TYPE_MAGNETIC_FIELD:
				case Sensor.TYPE_PRESSURE:
				case Sensor.TYPE_STEP_COUNTER:
				case Sensor.TYPE_PROXIMITY:
				case Sensor.TYPE_ORIENTATION:
					boolean result = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
					Log.i("sensor", "Registered Sensor: " + Boolean.toString(result));
					break;
				default:
					break;
				}
			}

			Criteria criteria = new Criteria();
			locationProvider = locationManager.getBestProvider(criteria, true);
			Log.i("location" + Integer.toString(startId), "Found provider: " + locationProvider);
			if (locationProvider != null) {
				locationManager.requestLocationUpdates(locationProvider, 10000, 100, this);

				// Initialize the location fields
				Location location = locationManager.getLastKnownLocation(locationProvider);
				if (location != null) {
					onLocationChanged(location);
				} else {
					// no location available
				}
			} else {
				// no location provider
			}

			IntentFilter filter = new IntentFilter("android.intent.action.ACTION_POWER_CONNECTED");
			filter.addAction(Intent.ACTION_BATTERY_CHANGED);
			filter.addAction("android.intent.action.ACTION_POWER_DISCONNECTED");
			this.registerReceiver(receiver, filter);

		} else if (action == "disconnect") {
			Log.i("service" + Integer.toString(startId), "action: disconnect");

			locationManager.removeUpdates(this);
			sensorManager.unregisterListener(this);
			this.unregisterReceiver(receiver);
			
			stopForeground(true);

			try {
				if (client != null) {
					client.disconnect();
				}
			} catch (MqttException me) {
				Log.i("mqtt" + Integer.toString(startId), "Disconnect Exception: " + me.getMessage());
			}

			broadcastConnectionStateChange(false);
		} else if (action == "status") {
			if(client==null) {
				broadcastConnectionStateChange(false);
			} else {
				broadcastConnectionStateChange(client.isConnected());
			}
		} else if (action == "switchAlarm") {
			boolean switchAlarm = intent.getBooleanExtra("state", false);
			Log.i("service" + Integer.toString(startId), "Got switch alarm: " + Boolean.toString(switchAlarm));
			if (client != null && client.isConnected()) {
				try {
					if (switchAlarm) {
						MqttMessage message = new MqttMessage();
						message.setPayload("1".getBytes());
						client.publish("me/alarm/switch", message);
					} else {
						MqttMessage message = new MqttMessage();
						message.setPayload("0".getBytes());
						client.publish("me/alarm/switch", message);
					}
				} catch (Exception e) {
					Log.i("sensor", e.getMessage());
				}
			}
		}

		return Service.START_NOT_STICKY;
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
		Log.i("service" + Integer.toString(startId), "connect()");
		try {
			if (client != null && client.isConnected()) {
				client.disconnect();
			}

			client = new MqttClient("ssl://" + server + ":8883", imei, new MemoryPersistence());

			MqttConnectOptions options = new MqttConnectOptions();
			options.setCleanSession(true);
			options.setUserName(imei);
			options.setPassword(apptoken.toCharArray());
			options.setKeepAliveInterval(60);
			client.setCallback(this);
			try {
				Log.i("mqtt" + Integer.toString(startId), "Connecting to server:" + server + " with apptoken:" + apptoken);
				client.connect(options);
				Log.i("mqtt" + Integer.toString(startId), "Connected...");
			} catch (MqttException me) {
				Log.i("mqtt" + Integer.toString(startId), "Connect Exception: " + me.getMessage());
			}

		} catch (MqttException e) {
			Log.i("mqtt" + Integer.toString(startId), "New Exception: " + e.getMessage());
		}
		broadcastConnectionStateChange(client.isConnected());
		return;
	}

	public void onSensorChanged(SensorEvent event) {

		try {
			String name = event.sensor.getName().toLowerCase().replace(" ", "").replace("&", "");
			Long lastPubTime = lastPublishMap.get(name);

			if (event.sensor.getType() == Sensor.TYPE_PROXIMITY && event.values.length == 3) {
				float proximity = event.values[0];
				// Log.i("proximity",
				// "Proximity Value: "+Float.toString(proximity));
				if (client != null && client.isConnected()) {
					if (proximity == 0.0 && lightProximity) {
						// Log.i("proximity", "Sending alarm: 1");
						MqttMessage message = new MqttMessage();
						message.setPayload("1".getBytes());
						client.publish("me/alarm/proximity", message);
						lightProximity = false;
					} else if (proximity != 0 && !lightProximity) {
						// Log.i("proximity", "Sending alarm: 1");
						MqttMessage message = new MqttMessage();
						message.setPayload("0".getBytes());
						client.publish("me/alarm/proximity", message);
						lightProximity = true;
					}
				}
			}

			if (lastPubTime == null || (lastPubTime + publish_interval < System.currentTimeMillis())) {
				lastPublishMap.put(name, System.currentTimeMillis());

				Log.i("sensor" + Integer.toString(startId), "Publishing: " + name);

				int len = event.values.length;

				if (client != null && client.isConnected()) {
					MqttMessage message = new MqttMessage();
					if (len == 1) {
						publishProperty(name, event.values[0]);
					} else if (len >= 3) {
						if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
							publishProperty(name, event.values[0]);
							if (event.values[1] == 0 && lightProximity) {
								message.setPayload("1".getBytes());
								client.publish("me/alarm/proximity", message);
								lightProximity = false;
							} else if (event.values[1] != 0 && !lightProximity) {
								message.setPayload("0".getBytes());
								client.publish("me/alarm/proximity", message);
								lightProximity = false;
							}
						} else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
							publishProperty(name, event.values[0]);
						} else if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
							publishProperty(name, event.values[0]);
						} else {
							publishProperty(name + "_x", event.values[0]);
							publishProperty(name + "_y", event.values[1]);
							publishProperty(name + "_z", event.values[2]);
						}
					}
				}
			}
		} catch (Exception e) {
			Log.i("sensor" + Integer.toString(startId), e.getMessage());
		}
	}

	public void publishProperty(String name, float value) {
		Intent intent = new Intent();
		intent.setAction("com.devicewise.SENSOR_UPDATE");
		intent.putExtra("name", name);
		intent.putExtra("value", value);
		sendBroadcast(intent);

		MqttMessage message = new MqttMessage();
		message.setPayload(Float.toString(value).getBytes());
		try {
			client.publish("me/property/" + name, message);
		} catch (Exception e) {
			Log.i("sensor" + Integer.toString(startId), e.getMessage());
		}
	}

	@Override
	public void onLocationChanged(Location location) {
		if (client == null || !client.isConnected()) {
			return;
		}
		Log.i("location" + Integer.toString(startId), "Publishing: location.");
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
			Log.i("location" + Integer.toString(startId), e.getMessage());
		}

		// TextView txt = (TextView) findViewById(R.id.textView2);
		// txt.setText("Lat:" + Double.toString(lat) + " Lng:" +
		// Double.toString(lng) + " (" + locationProvider + ")");
	}

	@Override
	public void connectionLost(Throwable cause) {
		// ToggleButton btn = (ToggleButton) findViewById(R.id.toggleButton1);
		// btn.setChecked(false);

		Log.i("mqtt" + Integer.toString(startId), "Connection Lost!");
		broadcastConnectionStateChange(false);
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
		Log.i("location" + Integer.toString(startId), "Status Change: " + provider + " / " + Integer.toString(status));
	}

	@Override
	public void onProviderEnabled(String provider) {
		Log.i("location" + Integer.toString(startId), "Provider Enabled: " + provider);
	}

	@Override
	public void onProviderDisabled(String provider) {
		Log.i("location" + Integer.toString(startId), "Provider Disabled: " + provider);
	}

}
