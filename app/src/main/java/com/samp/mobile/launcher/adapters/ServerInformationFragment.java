package com.samp.mobile.launcher.adapters;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.joom.paranoid.Obfuscate;
import com.samp.mobile.R;
import com.samp.mobile.launcher.util.ButtonAnimator;
import com.samp.mobile.launcher.util.SAMPServerInfo;
import com.samp.mobile.launcher.util.ServerLauncher;
import com.samp.mobile.launcher.util.SharedPreferenceCore;

import java.io.File;
import java.io.FileWriter;

@Obfuscate
public class ServerInformationFragment extends Dialog {

    ServerAdapter mServerAdapter;
    SAMPServerInfo sampServerInfo;
    int position;
    Activity activity;

    public ServerInformationFragment(Activity a, ServerAdapter adapter, int position, SAMPServerInfo sampServerInfo1)
    {
        super(a);
        activity = a;
        mServerAdapter = adapter;
        sampServerInfo = sampServerInfo1;
        this.position = position;
    }

    @Nullable
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.alertdialog_server);

        TextView mHostName = findViewById(R.id.server_hostname);
        TextView mIP = findViewById(R.id.server_ip);
        TextView mPort = findViewById(R.id.server_port);
        TextView mOnlineServer = findViewById(R.id.server_online);
        TextView mMode = findViewById(R.id.server_mode);
        TextView mLanguage = findViewById(R.id.server_language);
        ImageView mClose = findViewById(R.id.server_close);
        EditText mServerPassword = findViewById(R.id.server_password);
        Button mSave = findViewById(R.id.save_favorites);

        mSave.setVisibility(View.GONE);

        if(!sampServerInfo.getHasPassword())
            mServerPassword.setVisibility(View.GONE);
        else
            mServerPassword.setVisibility(View.VISIBLE);

        mHostName.setText(sampServerInfo.getServerName());

        mIP.setText(sampServerInfo.getAddress());
        mPort.setText(String.valueOf(sampServerInfo.getPort()));
        mOnlineServer.setText(sampServerInfo.getCurrentPlayerCount() + "/" + sampServerInfo.getMaxPlayerCount());
        mMode.setText(sampServerInfo.getServerMode());

        mLanguage.setText(sampServerInfo.getLanguage());

        Button mConnect = findViewById(R.id.server_connect);
        mConnect.setOnTouchListener(new ButtonAnimator(getContext(), mConnect));
        mConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!ServerLauncher.saveServer(activity,
                        sampServerInfo.getAddress(), sampServerInfo.getPort())) {
                    return;
                }

                if(new SharedPreferenceCore().getBoolean(getContext(), "MLOADER"))
                {
                    File file4 = new File(activity.getExternalMediaDirs() + "/monetloader/compat/profile.json");
                    Log.d("AXL", activity.getExternalMediaDirs() + "/monetloader/compat/profile.json");
                    if(file4.isDirectory())
                    {
                        file4.delete();
                    }
                    else if(!file4.exists())
                    {
                        file4.mkdir();
                        try {
                            FileWriter writer = new FileWriter(file4);
                            writer.append("{\n" +
                                    "  \"gtasa_name\": \"libGTASA.so\",\n" +
                                    "  \"profile_name\": \"SA-MP 0.3.7\",\n" +
                                    "  \"compat_scripts\": [],\n" +
                                    "  \"samp_name\": \"libsamp.so\",\n" +
                                    "  \"receiveignorerpc_pattern\": \"F0B503AF2DE900????B004460068C16A20468847\",\n" +
                                    "  \"cnetgame_ctor_pattern\": \"F0B503AF2DE9000788B00D46????9146????0446002079447A44\",\n" +
                                    "  \"rakclientinterface_netgame_offset\": 528,\n" +
                                    "  \"use_samp_touch_workaround\": true,\n" +
                                    "  \"nveventinsertnewest_offset\": 2606320\n" +
                                    "}");
                            writer.flush();
                            writer.close();
                        } catch (Exception e){
                            e.printStackTrace();
                        }
                    }
                }

                // The cache gate has already verified the game files. Do not
                // block the selected server on legacy app-private Text/Textures
                // paths: the current cache lives in /storage/emulated/0/GTA.
                if (ServerLauncher.startGame(activity)) {
                    dismiss();
                }
            }
        });


        mClose.setOnTouchListener(new ButtonAnimator(getContext(), mClose));
        mClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dismiss();
            }
        });
    }

}
