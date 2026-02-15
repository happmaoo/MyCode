package com.myapp.mymqtt;

import androidx.appcompat.app.AppCompatActivity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class settings extends AppCompatActivity {

    MyMQTT myapp;
    MyMQTT application;
    String mqqt_server_url;
    int mqqt_server_port;
    String mqqt_server_username;
    String mqqt_server_password;
    int mqqt_server_keepalive;
    String mqqt_topic;
    String fontsize;


    EditText editText_mqqt_server_url;
    EditText editText_mqqt_server_port;
    EditText editText_mqqt_server_username;
    EditText editText_mqqt_server_password;
    EditText editText_mqqt_server_keepalive;
    EditText editText_mqqt_topic,editText_FontSize;



    Button button_clearhistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);


        // SharedPreferences 全局保存
        myapp = (MyMQTT) getApplicationContext();

        mqqt_server_url = myapp.getString("mqqt_server_url","");
        mqqt_server_port = myapp.getInt("mqqt_server_port",0);
        mqqt_server_username = myapp.getString("mqqt_server_username","");
        mqqt_server_password = myapp.getString("mqqt_server_password","");
        mqqt_server_keepalive = myapp.getInt("mqqt_server_keepalive",60);
        mqqt_topic = myapp.getString("mqqt_topic","");
        fontsize = myapp.getString("fontsize","10");

        //mqqt_server_addr = myapp.getString("mqqt_server_addr","");

        editText_mqqt_server_url = findViewById(R.id.editText_mqqt_server_url);
        editText_mqqt_server_port = findViewById(R.id.editText_mqqt_server_port);
        editText_mqqt_server_username = findViewById(R.id.editText_mqqt_server_username);
        editText_mqqt_server_password = findViewById(R.id.editText_mqqt_server_password);
        editText_mqqt_server_keepalive = findViewById(R.id.editText_mqqt_server_keepalive);
        editText_mqqt_topic = findViewById(R.id.editText_mqqt_topic);

        editText_FontSize = findViewById(R.id.editText_FontSize);

        button_clearhistory = findViewById(R.id.button_clearhistory);

        editText_mqqt_server_url.setText(mqqt_server_url);
        editText_mqqt_server_port.setText(String.valueOf(mqqt_server_port));
        editText_mqqt_server_username.setText(mqqt_server_username);
        editText_mqqt_server_password.setText(mqqt_server_password);
        editText_mqqt_server_keepalive.setText(String.valueOf(mqqt_server_keepalive));
        editText_mqqt_topic.setText(mqqt_topic);
        editText_FontSize.setText(fontsize);


        button_clearhistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences prefs = getSharedPreferences("mqtt_history", MODE_PRIVATE);
                prefs.edit().clear().apply(); // apply() 比 commit() 更推荐，异步不卡顿
                myapp.historyList.clear();

            }
        });
    }


    @Override
    protected void onPause() {
        myapp.setString("mqqt_server_url",editText_mqqt_server_url.getText().toString());
        myapp.setInt("mqqt_server_port",Integer.parseInt(editText_mqqt_server_port.getText().toString()));
        myapp.setString("mqqt_server_username",editText_mqqt_server_username.getText().toString());
        myapp.setString("mqqt_server_password",editText_mqqt_server_password.getText().toString());
        myapp.setInt("mqqt_server_keepalive",Integer.parseInt(editText_mqqt_server_keepalive.getText().toString()));
        myapp.setString("mqqt_topic",editText_mqqt_topic.getText().toString());

        myapp.setString("fontsize",editText_FontSize.getText().toString());
        Toast.makeText(this,"已保存.",Toast.LENGTH_SHORT).show();
        super.onPause();

    }


}
