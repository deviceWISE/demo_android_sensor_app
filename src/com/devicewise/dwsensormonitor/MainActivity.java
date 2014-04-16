package com.devicewise.dwsensormonitor;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.os.Build;

public class MainActivity extends Activity implements OnCheckedChangeListener {

	String imei;

	private BroadcastReceiver receiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			Log.v("broadcast", "Got broadcast: " + action);

			if (action == "com.devicewise.CONNECTION_STATE") {
				ToggleButton btn = (ToggleButton) findViewById(R.id.toggleButtonConn);
				btn.setChecked(intent.getBooleanExtra("state", false));
			} else if (action == "com.devicewise.LOCATION_UPDATE") {
				TextView loc1 = (TextView) findViewById(R.id.textViewLoc1);
				TextView loc2 = (TextView) findViewById(R.id.textViewLoc2);
				loc1.setText(String.format("Lat: %.4f, Lng: %.4f", intent.getDoubleExtra("lat", 0.0), intent.getDoubleExtra("lng", 0.0)));
				loc2.setText(String.format("Provider: %s, Accuracy: %.2f", intent.getStringExtra("fix"), intent.getFloatExtra("acc", (float) 0.0)));
			} else if (action == "com.devicewise.SENSOR_UPDATE") {
				TextView log1 = (TextView) findViewById(R.id.textViewLog1);
				TextView log2 = (TextView) findViewById(R.id.textViewLog2);
				TextView log3 = (TextView) findViewById(R.id.textViewLog3);
				TextView log4 = (TextView) findViewById(R.id.textViewLog4);
				TextView log5 = (TextView) findViewById(R.id.textViewLog5);
				TextView log6 = (TextView) findViewById(R.id.textViewLog6);
				TextView log7 = (TextView) findViewById(R.id.textViewLog7);
				TextView log8 = (TextView) findViewById(R.id.textViewLog8);
				log8.setText(log7.getText());
				log7.setText(log6.getText());
				log6.setText(log5.getText());
				log5.setText(log4.getText());
				log4.setText(log3.getText());
				log3.setText(log2.getText());
				log2.setText(log1.getText());
				log1.setText(String.format("%s: %.4f", intent.getStringExtra("name"), intent.getFloatExtra("value", (float) 0.0)));
			}
		}

	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		imei = telephonyManager.getDeviceId();

		EditText txt3 = (EditText) findViewById(R.id.editTextImei);
		txt3.setText(imei);

	}

	public void onStart() {
		super.onStart();

		Switch s = (Switch) findViewById(R.id.switchAlarm);
		s.setOnCheckedChangeListener(this);

		IntentFilter filter = new IntentFilter("com.devicewise.CONNECTION_STATE");
		filter.addAction("com.devicewise.LOCATION_UPDATE");
		filter.addAction("com.devicewise.SENSOR_UPDATE");
		this.registerReceiver(receiver, filter);
		
		Intent i = new Intent(MainActivity.this, DwService.class);
		i.setAction("status");
		startService(i);
	}

	public void onStop() {
		this.unregisterReceiver(receiver);
		super.onStop();
	}

	public void ConnButtonOnClick(View v) {
		ToggleButton btn = (ToggleButton) v;

		if (!btn.isChecked()) {
			Log.i("connBtn", "Stopping service");
			Intent i = new Intent(MainActivity.this, DwService.class);
			i.setAction("disconnect");
			startService(i);
		} else {
			Log.i("connBtn", "Starting service");

			EditText txt1 = (EditText) findViewById(R.id.editTextServer);
			String server = txt1.getText().toString();

			EditText txt2 = (EditText) findViewById(R.id.editTextAppToken);
			String apptoken = txt2.getText().toString();

			Intent i = new Intent(MainActivity.this, DwService.class);
			i.setAction("connect");
			i.putExtra("server", server);
			i.putExtra("apptoken", apptoken);
			startService(i);
		}

		/*
		 * if (client.isConnected()) { btn.setChecked(true); } else {
		 * btn.setChecked(false); }
		 */
	}

	public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

		Intent i = new Intent(MainActivity.this, DwService.class);
		i.setAction("switchAlarm");
		i.putExtra("state", isChecked);
		startService(i);

		// Toast.makeText(this, "The Switch is " + (isChecked ? "on" : "off"),
		// Toast.LENGTH_SHORT).show();
		/*
		 * if (client == null || !client.isConnected()) { return; } try { if
		 * (isChecked) { MqttMessage message = new MqttMessage();
		 * message.setPayload("1".getBytes()); client.publish("me/alarm/switch",
		 * message); } else { MqttMessage message = new MqttMessage();
		 * message.setPayload("0".getBytes()); client.publish("me/alarm/switch",
		 * message); } } catch (Exception e) { Log.i("sensor_publisher",
		 * e.getMessage()); }
		 */
	}

}